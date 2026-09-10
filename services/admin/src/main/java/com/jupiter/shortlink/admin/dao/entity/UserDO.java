package com.jupiter.shortlink.admin.dao.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import com.fasterxml.jackson.annotation.JsonIgnore;
import com.jupiter.shortlink.admin.common.database.BaseDO;

import lombok.Data;
import lombok.ToString;

@Data
@TableName("t_user")
public class UserDO extends BaseDO {

    private Long id;

    /** 用户名 */
    private String username;

    /** 密码 */
    @JsonIgnore @ToString.Exclude private String password;

    /** Monotonic credential generation; sessions must match this value. */
    private Long authVersion;

    private Boolean disabled;

    /** 真实姓名 */
    private String realName;

    /** 手机号 */
    private String phone;

    /** 邮箱 */
    private String mail;

    /** 注销时间戳 */
    private Long deletionTime;
}
