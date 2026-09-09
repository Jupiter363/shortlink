package com.jupiter.shortlink.admin.service.impl;

import static com.jupiter.shortlink.admin.common.enums.UserErrorCodeEnum.USER_NAME_EXIST;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.jupiter.shortlink.admin.account.*;
import com.jupiter.shortlink.admin.common.biz.user.UserContext;
import com.jupiter.shortlink.admin.common.convention.exception.ClientException;
import com.jupiter.shortlink.admin.dao.entity.UserDO;
import com.jupiter.shortlink.admin.dao.mapper.UserMapper;
import com.jupiter.shortlink.admin.dto.req.UserLoginReqDTO;
import com.jupiter.shortlink.admin.dto.req.UserRegisterReqDTO;
import com.jupiter.shortlink.admin.dto.req.UserUpdateReqDTO;
import com.jupiter.shortlink.admin.dto.resp.UserLoginRespDTO;
import com.jupiter.shortlink.admin.dto.resp.UserRespDTO;
import com.jupiter.shortlink.admin.service.UserService;

import lombok.RequiredArgsConstructor;

import org.springframework.beans.BeanUtils;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Objects;

/** Account mutations and their recovery intents share one ds_0 transaction. */
@Service
@RequiredArgsConstructor
public class UserServiceImpl extends ServiceImpl<UserMapper, UserDO> implements UserService {
    private final AccountPasswordService passwords;
    private final AccountSessionStore sessions;
    private final AccountIntentRepository intents;

    @Override
    public UserRespDTO getUserByUsername(String username) {
        UserDO account = requireCurrentAccount(username, false);
        UserRespDTO response = new UserRespDTO();
        BeanUtils.copyProperties(account, response);
        return response;
    }

    @Override
    public Boolean hasUsername(String username) {
        validateUsername(username);
        // Database uniqueness is the account authority.
        return baseMapper.selectCount(
                        Wrappers.lambdaQuery(UserDO.class).eq(UserDO::getUsername, username))
                > 0;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void register(UserRegisterReqDTO request) {
        if (request == null) throw new ClientException("注册信息不能为空");
        validateUsername(request.getUsername());
        validateProfile(request.getRealName(), request.getPhone(), request.getMail());
        if (hasUsername(request.getUsername())) throw new ClientException(USER_NAME_EXIST);
        UserDO account = new UserDO();
        account.setUsername(request.getUsername());
        account.setPassword(passwords.encode(request.getPassword()));
        account.setRealName(request.getRealName());
        account.setPhone(request.getPhone());
        account.setMail(request.getMail());
        account.setAuthVersion(1L);
        account.setDisabled(false);
        account.setDeletionTime(0L);
        account.setDelFlag(0);
        try {
            account.setId(
                    intents.reserveIdentity(account.getUsername(), System.currentTimeMillis()));
            if (baseMapper.insert(account) != 1
                    || account.getId() == null
                    || account.getId() <= 0) {
                throw new IllegalStateException("Account insert failed");
            }
        } catch (DuplicateKeyException exists) {
            throw new ClientException(USER_NAME_EXIST);
        }
        intents.createInitialization(
                account.getId(), account.getUsername(), System.currentTimeMillis());
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void update(UserUpdateReqDTO request) {
        if (request == null) throw new ClientException("更新信息不能为空");
        UserDO account = requireCurrentAccount(request.getUsername(), true);
        validateProfile(request.getRealName(), request.getPhone(), request.getMail());
        boolean changePassword = request.getPassword() != null;
        boolean disable = Boolean.TRUE.equals(request.getDisabled());
        if ((changePassword || disable)
                && !passwords.matches(request.getCurrentPassword(), account.getPassword())) {
            throw new ClientException("当前密码错误");
        }
        if (request.getRealName() != null) account.setRealName(request.getRealName());
        if (request.getPhone() != null) account.setPhone(request.getPhone());
        if (request.getMail() != null) account.setMail(request.getMail());
        if (changePassword) account.setPassword(passwords.encode(request.getPassword()));
        if (disable) account.setDisabled(true);
        long oldVersion = account.getAuthVersion();
        if (changePassword || disable) account.setAuthVersion(Math.addExact(oldVersion, 1));
        int changed =
                baseMapper.update(
                        account,
                        Wrappers.lambdaUpdate(UserDO.class)
                                .eq(UserDO::getUsername, account.getUsername())
                                .eq(UserDO::getId, account.getId())
                                .eq(UserDO::getAuthVersion, oldVersion)
                                .eq(UserDO::getDelFlag, 0));
        if (changed != 1) throw new ClientException("账号状态已变化，请重新登录");
        if (changePassword || disable) {
            intents.createSessionCleanup(
                    account.getId(),
                    account.getUsername(),
                    account.getAuthVersion(),
                    System.currentTimeMillis());
        }
    }

    @Override
    public UserLoginRespDTO login(UserLoginReqDTO request) {
        if (request == null) throw new ClientException("登录信息不能为空");
        validateUsername(request.getUsername());
        UserDO account = loadAccount(request.getUsername(), false);
        boolean matched =
                passwords.matches(
                        request.getPassword(), account == null ? null : account.getPassword());
        if (!matched || !active(account)) throw new ClientException("用户名或密码错误");
        String token =
                sessions.issue(account.getId(), account.getUsername(), account.getAuthVersion());
        try {
            // A credential change may commit between hash comparison and Redis issuance.
            UserDO current = loadAccount(account.getUsername(), false);
            if (!active(current)
                    || !Objects.equals(current.getId(), account.getId())
                    || !Objects.equals(current.getAuthVersion(), account.getAuthVersion())) {
                throw new ClientException("账号状态已变化，请重新登录");
            }
            return new UserLoginRespDTO(token);
        } catch (RuntimeException rejected) {
            try {
                sessions.revoke(account.getUsername(), token);
            } catch (RuntimeException ignored) {
            }
            // Even if Redis cleanup is unavailable, backends reject a stale authVersion.
            throw rejected;
        }
    }

    @Override
    public void logout(String username, String token) {
        validateUsername(username);
        // Removing exactly this bearer token is idempotent and cannot sign out another device.
        sessions.revoke(username, token);
    }

    @Override
    public Boolean checkLogin(String username, String token) {
        validateUsername(username);
        AccountSession session = sessions.find(username, token);
        if (session == null) return false;
        UserDO current = loadAccount(username, false);
        return active(current)
                && current.getId() == session.tenantId()
                && current.getAuthVersion() == session.authVersion();
    }

    @Override
    public AccountInitializationStatus initializationStatus() {
        UserDO account = requireCurrentAccount(null, false);
        return AccountInitializationStatus.from(
                intents.findInitialization(account.getId())
                        .orElseThrow(
                                () ->
                                        new IllegalStateException(
                                                "Account initialization intent missing")));
    }

    @Override
    public AccountInitializationStatus retryInitialization() {
        UserDO account = requireCurrentAccount(null, false);
        intents.requestRetry(account.getId(), System.currentTimeMillis());
        return AccountInitializationStatus.from(
                intents.findInitialization(account.getId())
                        .orElseThrow(
                                () ->
                                        new IllegalStateException(
                                                "Account initialization intent missing")));
    }

    private UserDO requireCurrentAccount(String requestedUsername, boolean lock) {
        String username = UserContext.getUsername();
        String tenantId = UserContext.getUserId();
        Long authVersion = UserContext.getAuthVersion();
        if (username == null
                || tenantId == null
                || authVersion == null
                || (requestedUsername != null && !username.equals(requestedUsername))) {
            throw new ClientException("仅允许访问当前登录账号");
        }
        UserDO account = loadAccount(username, lock);
        if (!active(account)
                || !account.getId().toString().equals(tenantId)
                || !authVersion.equals(account.getAuthVersion()))
            throw new ClientException("登录状态已失效");
        return account;
    }

    private UserDO loadAccount(String username, boolean lock) {
        var query = Wrappers.lambdaQuery(UserDO.class).eq(UserDO::getUsername, username);
        if (lock) query.last("FOR UPDATE");
        return baseMapper.selectOne(query);
    }

    private static boolean active(UserDO account) {
        return account != null
                && account.getId() != null
                && account.getId() > 0
                && Integer.valueOf(0).equals(account.getDelFlag())
                && Boolean.FALSE.equals(account.getDisabled())
                && account.getAuthVersion() != null
                && account.getAuthVersion() > 0;
    }

    private static void validateUsername(String username) {
        if (username == null || !username.matches("[A-Za-z0-9_-]{3,64}"))
            throw new ClientException("用户名应为3至64位字母、数字、下划线或连字符");
    }

    private static void validateProfile(String realName, String phone, String mail) {
        if ((realName != null && realName.length() > 64)
                || (phone != null && phone.length() > 32)
                || (mail != null && mail.length() > 254)) throw new ClientException("账号资料超出长度限制");
    }
}
