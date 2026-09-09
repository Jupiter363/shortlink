-- Prepare the initial FD table in ordinary Linux x64 workers before traffic.
-- No HTTP/Kafka traffic, limit changes, dup2, timers, shared dictionaries or retries.
local M = {}
local MIN_FD, SOURCE_FD = 1023, 2
local F_GETFD, F_DUPFD_CLOEXEC, RLIMIT_NOFILE, EBADF = 1, 1030, 7, 9
local attempted, saved = false, nil

local function copy(value)
    local out = {}
    for k, v in pairs(value or {}) do out[k] = v end
    return out
end

local function integer(value, minimum, maximum)
    return type(value) == "number" and value >= minimum and value <= maximum
        and value == math.floor(value)
end

local function fail(code)
    return {ok = false, skipped = false, status = code, attempted = false}
end

function M.snapshot()
    return copy(saved or {ok = false, status = "NOT_ATTEMPTED", attempted = false})
end

function M.init(minimum_fd)
    if minimum_fd == nil then minimum_fd = MIN_FD end
    if not integer(minimum_fd, MIN_FD, MIN_FD) then return fail("INVALID_MINIMUM_FD") end
    if type(ngx) ~= "table" or type(ngx.get_phase) ~= "function" then return fail("NGX_UNAVAILABLE") end
    local phase_ok, phase = pcall(ngx.get_phase)
    if not phase_ok or phase ~= "init_worker" then return fail("INIT_WORKER_PHASE_REQUIRED") end
    local process_ok, process = pcall(require, "ngx.process")
    if not process_ok or type(process) ~= "table" or type(process.type) ~= "function" then return fail("PROCESS_TYPE_UNAVAILABLE") end
    local type_ok, kind = pcall(process.type)
    if not type_ok then return fail("PROCESS_TYPE_UNAVAILABLE") end
    if kind ~= "worker" then
        return {ok = true, skipped = true, status = "NOT_ORDINARY_WORKER", attempted = false}
    end
    if type(ngx.worker) ~= "table" or type(ngx.worker.id) ~= "function"
        or type(ngx.worker.pid) ~= "function" or type(ngx.worker.count) ~= "function"
        or type(ngx.worker.exiting) ~= "function" then return fail("WORKER_IDENTITY_UNAVAILABLE") end
    local identity_ok, id, pid, count, exiting = pcall(function()
        return ngx.worker.id(), ngx.worker.pid(), ngx.worker.count(), ngx.worker.exiting()
    end)
    if not identity_ok or not integer(count, 1, 1024) or not integer(id, 0, count - 1)
        or not integer(pid, 2, 2147483647) then return fail("WORKER_IDENTITY_UNAVAILABLE") end
    if exiting then return fail("WORKER_EXITING") end
    if attempted then
        if saved.workerPid ~= pid or saved.workerId ~= id then return fail("INITIALIZED_IN_ANOTHER_PROCESS") end
        local prior = copy(saved)
        prior.alreadyAttempted = true
        return prior
    end
    attempted = true
    local result = {ok = false, skipped = false, status = "INITIALIZING", attempted = true,
        workerId = id, workerPid = pid, workerCount = count, sourceFd = SOURCE_FD,
        requestedMinimumFd = MIN_FD, createdFd = false, closeAttempted = false,
        closeSucceeded = false, limitModified = false, businessRequests = 0}
    saved = result
    local owned_fd, native_close, ffi
    local ran, error_code = pcall(function()
        ffi = require("ffi")
        if ffi.os ~= "Linux" or ffi.arch ~= "x64" or not ffi.abi("64bit") then error("LINUX_X64_REQUIRED", 0) end
        local declarations = {
            {"fcntl", "int fcntl(int fd, int cmd, ...);"},
            {"close", "int close(int fd);"},
            {"getrlimit", "int getrlimit(int resource, void *limits);"},
        }
        for _, declaration in ipairs(declarations) do
            if not pcall(function() return ffi.C[declaration[1]] end) then ffi.cdef(declaration[2]) end
        end
        local native_fcntl = ffi.cast("int (*)(int, int, ...)", ffi.C.fcntl)
        native_close = ffi.cast("int (*)(int)", ffi.C.close)
        local native_getrlimit = ffi.cast("int (*)(int, void *)", ffi.C.getrlimit)
        local limits = ffi.new("struct { unsigned long current; unsigned long maximum; }[1]")
        if native_getrlimit(RLIMIT_NOFILE, limits) ~= 0 then
            result.errno = ffi.errno(); error("GETRLIMIT_FAILED", 0)
        end
        local soft, hard = tonumber(limits[0].current), tonumber(limits[0].maximum)
        result.softLimit, result.hardLimit = soft, hard
        if not soft or not hard or soft < MIN_FD + 1 or hard < soft then error("NOFILE_LIMIT_TOO_SMALL_OR_INVALID", 0) end
        local source_flags = tonumber(native_fcntl(SOURCE_FD, F_GETFD))
        if source_flags < 0 then result.errno = ffi.errno(); error("SOURCE_FD_INVALID", 0) end
        result.sourceFlags = source_flags
        -- If this high descriptor already exists, the table is already large enough.
        -- Never close or replace it. This also avoids an unnecessary larger allocation.
        local target_flags = tonumber(native_fcntl(MIN_FD, F_GETFD))
        if target_flags >= 0 then
            result.capacityAtLeast = MIN_FD + 1; result.status = "ALREADY_CAPACITY_PRESENT"
            result.ok = true; return
        end
        if ffi.errno() ~= EBADF then result.errno = ffi.errno(); error("TARGET_FD_PROBE_FAILED", 0) end
        -- Lua numbers are doubles in varargs; an explicit int cdata is required.
        local allocated = tonumber(native_fcntl(SOURCE_FD, F_DUPFD_CLOEXEC, ffi.new("int", MIN_FD)))
        if allocated < 0 then result.errno = ffi.errno(); error("DUPLICATE_FAILED", 0) end
        if not integer(allocated, MIN_FD, 2147483647) or allocated == SOURCE_FD then
            error("DUPLICATE_RETURN_UNSAFE_TO_CLOSE", 0)
        end
        owned_fd = allocated -- Only this successful duplication proves ownership.
        result.createdFd, result.allocatedFd = true, allocated
        result.capacityAtLeast = allocated + 1
        if allocated >= soft then error("DUPLICATE_EXCEEDS_SOFT_LIMIT", 0) end
        local duplicate_flags = tonumber(native_fcntl(allocated, F_GETFD))
        if duplicate_flags < 0 then result.errno = ffi.errno(); error("DUPLICATE_FLAGS_FAILED", 0) end
        if duplicate_flags % 2 ~= 1 then error("DUPLICATE_CLOEXEC_MISSING", 0) end
        if tonumber(native_fcntl(SOURCE_FD, F_GETFD)) ~= source_flags then error("SOURCE_FD_FLAGS_CHANGED", 0) end
        result.status = "PREALLOCATED"
        result.ok = true
    end)
    if not ran then
        result.ok = false
        result.status = type(error_code) == "string" and error_code:match("^[A-Z0-9_]+$")
            and error_code or "FFI_EXECUTION_FAILED"
    end
    if owned_fd ~= nil then
        result.closeAttempted = true
        local closed, rc = pcall(native_close, owned_fd)
        result.closeSucceeded = closed and tonumber(rc) == 0
        if not result.closeSucceeded then
            result.previousStatus = result.status
            result.status, result.ok = "CLOSE_FAILED", false
            if closed then result.closeErrno = ffi.errno() end
            -- On Linux an EINTR/error may follow descriptor release. Never retry close.
            result.closeNotRetried = true
        end
    end
    return copy(result)
end

return M
