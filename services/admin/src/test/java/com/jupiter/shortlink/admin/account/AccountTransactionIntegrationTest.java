package com.jupiter.shortlink.admin.account;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.extension.spring.MybatisSqlSessionFactoryBean;
import com.jupiter.shortlink.admin.common.biz.user.UserContext;
import com.jupiter.shortlink.admin.common.biz.user.UserInfoDTO;
import com.jupiter.shortlink.admin.common.convention.exception.ClientException;
import com.jupiter.shortlink.admin.dao.mapper.UserMapper;
import com.jupiter.shortlink.admin.dto.req.UserRegisterReqDTO;
import com.jupiter.shortlink.admin.dto.req.UserUpdateReqDTO;
import com.jupiter.shortlink.admin.service.UserService;
import com.jupiter.shortlink.admin.service.impl.UserServiceImpl;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mybatis.spring.SqlSessionTemplate;
import org.springframework.aop.framework.ProxyFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.annotation.AnnotationTransactionAttributeSource;
import org.springframework.transaction.interceptor.TransactionInterceptor;

import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Real JDBC + MyBatis + Spring transactions. Defaults to isolated H2, accepts an explicitly named
 * test MySQL schema.
 */
class AccountTransactionIntegrationTest {
    private JdbcTemplate jdbc;
    private AccountIntentRepository intents;
    private AccountPasswordService passwords;
    private UserService service;
    private AccountSessionStore sessions;
    private String username;

    @BeforeEach
    void setUp() throws Exception {
        String url =
                System.getProperty(
                        "account.it.jdbc-url",
                        "jdbc:h2:mem:account_"
                                + UUID.randomUUID()
                                + ";MODE=MySQL;DB_CLOSE_DELAY=-1");
        if (!url.startsWith("jdbc:h2:mem:")
                && !url.matches(
                        "jdbc:mysql://127\\.0\\.0\\.1:(3306|13306)/shortlink_account_it(?:\\?.*)?")) {
            throw new IllegalArgumentException(
                    "Account integration tests require an isolated shortlink_account_it database on"
                        + " loopback");
        }
        DriverManagerDataSource datasource =
                new DriverManagerDataSource(
                        url,
                        System.getProperty("account.it.user", "sa"),
                        System.getProperty("account.it.password", ""));
        jdbc = new JdbcTemplate(datasource);
        jdbc.execute(
                "CREATE TABLE IF NOT EXISTS t_account_identity (id BIGINT AUTO_INCREMENT PRIMARY"
                    + " KEY, username VARCHAR(64) NOT NULL UNIQUE, created_at BIGINT NOT NULL)");
        jdbc.execute(
                """
CREATE TABLE IF NOT EXISTS t_user (
 id BIGINT PRIMARY KEY, username VARCHAR(64) NOT NULL UNIQUE, password VARCHAR(100) NOT NULL,
 auth_version BIGINT NOT NULL DEFAULT 1, disabled BOOLEAN NOT NULL DEFAULT FALSE,
 real_name VARCHAR(64), phone VARCHAR(32), mail VARCHAR(254), deletion_time BIGINT,
 create_time TIMESTAMP NULL, update_time TIMESTAMP NULL, del_flag INT NOT NULL DEFAULT 0)
""");
        jdbc.execute(
                """
CREATE TABLE IF NOT EXISTS t_account_initialization (
 tenant_id BIGINT PRIMARY KEY, username VARCHAR(64) NOT NULL, command_id VARCHAR(96) NOT NULL UNIQUE,
 state VARCHAR(16) NOT NULL, group_id VARCHAR(64), attempts INT NOT NULL DEFAULT 0,
 next_attempt_at BIGINT NOT NULL, lease_until BIGINT NOT NULL DEFAULT 0, claim_version BIGINT NOT NULL DEFAULT 0,
 last_error VARCHAR(128), created_at BIGINT NOT NULL, updated_at BIGINT NOT NULL)
""");
        jdbc.execute(
                """
CREATE TABLE IF NOT EXISTS t_session_cleanup_intent (
 tenant_id BIGINT NOT NULL, auth_version BIGINT NOT NULL, username VARCHAR(64) NOT NULL, created_at BIGINT NOT NULL,
 PRIMARY KEY(tenant_id, auth_version))
""");
        MybatisConfiguration configuration = new MybatisConfiguration();
        configuration.setMapUnderscoreToCamelCase(true);
        configuration.addMapper(UserMapper.class);
        MybatisSqlSessionFactoryBean factory = new MybatisSqlSessionFactoryBean();
        factory.setDataSource(datasource);
        factory.setConfiguration(configuration);
        SqlSessionTemplate template = new SqlSessionTemplate(factory.getObject());
        intents = spy(new AccountIntentRepository(jdbc));
        passwords = new AccountPasswordService(2, 10);
        sessions = mock(AccountSessionStore.class);
        UserServiceImpl target = new UserServiceImpl(passwords, sessions, intents);
        ReflectionTestUtils.setField(target, "baseMapper", template.getMapper(UserMapper.class));
        ProxyFactory proxy = new ProxyFactory(target);
        proxy.setProxyTargetClass(true);
        proxy.addAdvice(
                new TransactionInterceptor(
                        new DataSourceTransactionManager(datasource),
                        new AnnotationTransactionAttributeSource()));
        service = (UserService) proxy.getProxy();
        username = "test_" + UUID.randomUUID().toString().replace("-", "");
    }

    @AfterEach
    void clearPrincipal() {
        UserContext.removeUser();
    }

    @Test
    void registrationPersistsSaltedAccountAndInitializationInOneTransactionWithoutLoginContext() {
        service.register(registration());
        long id = id();
        String encoded =
                jdbc.queryForObject(
                        "SELECT password FROM t_user WHERE username = ?", String.class, username);
        assertTrue(passwords.matches("initial-password", encoded));
        AccountInitialization initialization = intents.findInitialization(id).orElseThrow();
        assertEquals("PENDING", initialization.state());
        assertEquals("account-default-group:" + id, initialization.commandId());
        assertThrows(ClientException.class, () -> service.register(registration()));
        assertEquals(
                1,
                jdbc.queryForObject(
                        "SELECT COUNT(*) FROM t_user WHERE username = ?", Integer.class, username));
        verifyNoInteractions(sessions);
    }

    @Test
    void failedIntentInsertRollsBackAccountInsert() {
        doThrow(new IllegalStateException("injected intent storage failure"))
                .when(intents)
                .createInitialization(anyLong(), eq(username), anyLong());
        assertThrows(IllegalStateException.class, () -> service.register(registration()));
        assertEquals(
                0,
                jdbc.queryForObject(
                        "SELECT COUNT(*) FROM t_user WHERE username = ?", Integer.class, username));
        assertEquals(
                0,
                jdbc.queryForObject(
                        "SELECT COUNT(*) FROM t_account_identity WHERE username = ?",
                        Integer.class,
                        username));
    }

    @Test
    void passwordCommitIncludesDurableRevocationAndRejectsStaleSessionBeforeRedisCleanup() {
        service.register(registration());
        long id = id();
        UserContext.setUser(new UserInfoDTO(Long.toString(id), username, null, 1L));
        UserUpdateReqDTO update = passwordUpdate();
        service.update(update);
        assertEquals(
                2L,
                jdbc.queryForObject(
                        "SELECT auth_version FROM t_user WHERE username = ?",
                        Long.class,
                        username));
        assertEquals(
                1,
                jdbc.queryForObject(
                        "SELECT COUNT(*) FROM t_session_cleanup_intent WHERE tenant_id = ? AND"
                            + " auth_version = 2",
                        Integer.class,
                        id));
        String hash =
                jdbc.queryForObject(
                        "SELECT password FROM t_user WHERE username = ?", String.class, username);
        assertTrue(passwords.matches("updated-password", hash));
        assertFalse(passwords.matches("initial-password", hash));
        when(sessions.find(username, "old-token"))
                .thenReturn(new AccountSession(id, username, 1, Long.MAX_VALUE));
        assertFalse(service.checkLogin(username, "old-token"));
        assertThrows(ClientException.class, () -> service.update(update));
    }

    @Test
    void revocationIntentFailureRollsBackPasswordAndVersion() {
        service.register(registration());
        long id = id();
        UserContext.setUser(new UserInfoDTO(Long.toString(id), username, null, 1L));
        doThrow(new IllegalStateException("injected revocation storage failure"))
                .when(intents)
                .createSessionCleanup(eq(id), eq(username), eq(2L), anyLong());
        assertThrows(IllegalStateException.class, () -> service.update(passwordUpdate()));
        assertEquals(
                1L,
                jdbc.queryForObject(
                        "SELECT auth_version FROM t_user WHERE username = ?",
                        Long.class,
                        username));
        assertTrue(
                passwords.matches(
                        "initial-password",
                        jdbc.queryForObject(
                                "SELECT password FROM t_user WHERE username = ?",
                                String.class,
                                username)));
    }

    @Test
    void initializationAcknowledgementLossRecoversWithSameCommandIdentityAndStaleWorkerIsFenced() {
        service.register(registration());
        long id = id();
        AccountInitialization initial = intents.findInitialization(id).orElseThrow();
        ConcurrentHashMap<String, String> committedCommands = new ConcurrentHashMap<>();
        AtomicBoolean loseFirstAcknowledgement = new AtomicBoolean(true);
        DefaultGroupCommandClient client =
                item -> {
                    String gid =
                            committedCommands.computeIfAbsent(
                                    item.commandId(), ignored -> "default_gid");
                    if (loseFirstAcknowledgement.getAndSet(false))
                        throw new IllegalStateException("committed but ACK lost");
                    return gid;
                };
        AccountRecoveryWorker worker = new AccountRecoveryWorker(intents, client, sessions);
        worker.recover(initial);
        assertEquals("FAILED", intents.findInitialization(id).orElseThrow().state());
        intents.requestRetry(id, System.currentTimeMillis());
        worker.recover(intents.findInitialization(id).orElseThrow());
        assertEquals("READY", intents.findInitialization(id).orElseThrow().state());
        assertEquals("default_gid", intents.findInitialization(id).orElseThrow().groupId());
        assertEquals(1, committedCommands.size());
        assertFalse(intents.complete(initial, "stale_gid", System.currentTimeMillis()));
    }

    @Test
    void disableBumpsVersionAndCreatesCleanupIntent() {
        service.register(registration());
        long id = id();
        UserContext.setUser(new UserInfoDTO(Long.toString(id), username, null, 1L));
        UserUpdateReqDTO update = new UserUpdateReqDTO();
        update.setDisabled(true);
        update.setCurrentPassword("initial-password");
        service.update(update);
        assertTrue(
                jdbc.queryForObject(
                        "SELECT disabled FROM t_user WHERE username = ?", Boolean.class, username));
        assertEquals(
                2L,
                jdbc.queryForObject(
                        "SELECT auth_version FROM t_user WHERE username = ?",
                        Long.class,
                        username));
        assertThrows(ClientException.class, service::initializationStatus);
    }

    private long id() {
        return jdbc.queryForObject(
                "SELECT id FROM t_user WHERE username = ?", Long.class, username);
    }

    private UserRegisterReqDTO registration() {
        UserRegisterReqDTO request = new UserRegisterReqDTO();
        request.setUsername(username);
        request.setPassword("initial-password");
        request.setRealName("test");
        return request;
    }

    private UserUpdateReqDTO passwordUpdate() {
        UserUpdateReqDTO request = new UserUpdateReqDTO();
        request.setUsername(username);
        request.setCurrentPassword("initial-password");
        request.setPassword("updated-password");
        return request;
    }
}
