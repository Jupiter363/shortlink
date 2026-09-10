package com.jupiter.shortlink.admin.dto.req;

import lombok.Data;
import lombok.ToString;

/** 用户注册请求参数 */
@Data
public class UserUpdateReqDTO {
    /** 用户名 */
    private String username;

    /** 密码 */
    @ToString.Exclude private String password;

    /** Required when changing the password or disabling this account. */
    @ToString.Exclude private String currentPassword;

    /** Self-service disable is allowed; a disabled account cannot re-enable itself. */
    private Boolean disabled;

    /** 真实姓名 */
    private String realName;

    /** 手机号 */
    private String phone;

    /** 邮箱 */
    private String mail;
}
