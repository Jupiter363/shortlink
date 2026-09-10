package com.jupiter.shortlink.admin.dto.req;

import lombok.Data;
import lombok.ToString;

/** 用户登录请求参数 */
@Data
public class UserLoginReqDTO {

    /** 用户名 */
    private String username;

    /** 密码 */
    @ToString.Exclude private String password;
}
