package com.jupiter.shortlink.admin.controller;

import com.jupiter.shortlink.admin.account.AccountInitializationStatus;
import com.jupiter.shortlink.admin.common.biz.user.UserTransmitFilter;
import jakarta.servlet.http.HttpServletRequest;
import com.jupiter.shortlink.admin.common.convention.result.Result;
import com.jupiter.shortlink.admin.common.convention.result.Results;
import com.jupiter.shortlink.admin.common.enums.UserErrorCodeEnum;
import com.jupiter.shortlink.admin.dto.req.UserLoginReqDTO;
import com.jupiter.shortlink.admin.dto.req.UserRegisterReqDTO;
import com.jupiter.shortlink.admin.dto.req.UserUpdateReqDTO;
import com.jupiter.shortlink.admin.dto.resp.UserLoginRespDTO;
import com.jupiter.shortlink.admin.dto.resp.UserRespDTO;
import com.jupiter.shortlink.admin.service.UserService;

import lombok.RequiredArgsConstructor;

import org.springframework.web.bind.annotation.*;

/*用户控制管理层*/
@RestController
@RequiredArgsConstructor
public class UserController {

    private final UserService userService;

    @GetMapping("/api/short-link/admin/v1/user/initialization")
    public Result<AccountInitializationStatus> initializationStatus() {
        return Results.success(userService.initializationStatus());
    }

    @PostMapping("/api/short-link/admin/v1/user/initialization/retry")
    public Result<AccountInitializationStatus> retryInitialization() {
        return Results.success(userService.retryInitialization());
    }

    /** 根据用户名查用户信息 */
    @GetMapping("/api/short-link/v1/user/{username}")
    public Result<UserRespDTO> getUserByUserName(@PathVariable("username") String username) {
        UserRespDTO result = userService.getUserByUsername(username);
        if (result == null) {
            return new Result<UserRespDTO>()
                    .setCode(UserErrorCodeEnum.USER_NULL.code())
                    .setMessage(UserErrorCodeEnum.USER_NULL.message());
        } else {
            return Results.success(result);
        }
    }

    @GetMapping("/api/short-link/v1/user/has-username")
    public Result<Boolean> hasUsername(@RequestParam("username") String username) {
        return Results.success(userService.hasUsername(username));
    }

    /** 注册用户 */
    @PostMapping("/api/short-link/admin/v1/user")
    public Result<Void> register(@RequestBody UserRegisterReqDTO requestParam) {
        userService.register(requestParam);
        return Results.success();
    }

    /** 修改用户 */
    @PutMapping("/api/short-link/v1/user")
    public Result<Void> update(@RequestBody UserUpdateReqDTO requestParam) {
        userService.update(requestParam);
        return Results.success();
    }

    /** 用户登录 */
    @PostMapping("/api/short-link/admin/v1/user/login")
    public Result<UserLoginRespDTO> login(@RequestBody UserLoginReqDTO requestParam) {
        return Results.success(userService.login(requestParam));
    }

    /** 用户退出登录 */
    @DeleteMapping("/api/short-link/admin/v1/user/logout")
    public Result<Void> logout(@RequestParam String username, @RequestParam String token) {
        userService.logout(username, token);
        return Results.success();
    }

    /** 检查用户是否登录 */
    @GetMapping("/api/short-link/v1/user/check-login")
    public Result<Boolean> checkLogin(
            @RequestParam("username") String username, @RequestParam("token") String token,
            HttpServletRequest request) {
        // The ingress already checked this exact session and the current MySQL account generation.
        return Results.success(UserTransmitFilter.verifiedSessionMatches(request, username, token));
    }
}
