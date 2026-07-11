local current = redis.call('get', KEYS[1])
if not current then
    return 0
end
if current ~= ARGV[1] then
    return -1
end
redis.call('del', KEYS[1])
return 1
