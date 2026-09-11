package com.jupiter.shortlink.admin.common.biz.user;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

import com.jupiter.shortlink.admin.account.RedisAccountSessionStore;
import com.jupiter.shortlink.admin.account.AccountSessionStore;
import com.jupiter.shortlink.admin.dao.entity.UserDO;
import com.jupiter.shortlink.admin.dao.mapper.UserMapper;
import com.jupiter.shortlink.admin.config.UserConfiguration;
import org.junit.jupiter.api.*;
import org.springframework.data.redis.connection.RedisStandaloneConfiguration;
import org.springframework.data.redis.connection.lettuce.LettuceClientConfiguration;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import java.time.Duration;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.InetAddress;
import java.io.IOException;
import java.util.concurrent.*;

/** Migrated GatewaySessionRedisIT: an explicit dedicated Redis is mandatory, never silently skipped. */
class AdminSessionRedisIT {
    LettuceConnectionFactory connection;
    StringRedisTemplate redis;
    RedisAccountSessionStore sessions;
    AccountSessionStore observedSessions;
    UserTransmitFilter filter;
    UserMapper users;
    UserDO account;
    String username, token, key;

    @BeforeEach void connect() throws Exception {
        String port=System.getenv("SHORTLINK_REDIS_TEST_PORT");
        assertNotNull(port,"Set SHORTLINK_REDIS_TEST_PORT to a dedicated isolated loopback Redis");
        var properties=new AdminIngressProperties();
        properties.setAllowedHosts(List.of("admin.example"));
        properties.setTrustedProxyCidrs(List.of("127.0.0.0/8"));
        var client=LettuceClientConfiguration.builder();
        new UserConfiguration().boundedAdminRedis(properties).customize(client);
        assertEquals(Duration.ofMillis(150),client.build().getCommandTimeout());
        connection=new LettuceConnectionFactory(new RedisStandaloneConfiguration("127.0.0.1",Integer.parseInt(port)),client.build());
        connection.afterPropertiesSet();
        redis=new StringRedisTemplate(connection);redis.afterPropertiesSet();
        awaitFixtureReady();
        sessions=new RedisAccountSessionStore(redis);
        observedSessions=mock(AccountSessionStore.class, org.mockito.AdditionalAnswers.delegatesTo(sessions));
        username="admin_it_"+UUID.randomUUID().toString().replace("-","");
        key="login_"+username;token="session_"+UUID.randomUUID();
        users=mock(UserMapper.class);
        account=new UserDO();account.setId(42L);account.setUsername(username);
        account.setAuthVersion(3L);account.setDisabled(false);account.setDelFlag(0);
        when(users.selectOne(any())).thenAnswer(invocation->account);
        filter=new UserTransmitFilter(new TrustedManagementIdentity(users),observedSessions,properties,8102);
    }
    private void awaitFixtureReady() throws InterruptedException {
        // Initial Netty/class loading is fixture preparation, not the session operation under test.
        // Keep the production 150ms command/1s connect budgets unchanged on every attempt.
        long started = System.nanoTime(), deadline = started + Duration.ofSeconds(15).toNanos();
        RuntimeException last = null;
        int attempts = 0;
        do {
            attempts++;
            try (var channel = connection.getConnection()) {
                if ("PONG".equals(channel.ping())) {
                    System.out.printf("admin_redis_it_ready attempts=%d elapsed_ms=%d%n", attempts,
                            Duration.ofNanos(System.nanoTime()-started).toMillis());
                    return;
                }
            } catch (RuntimeException unavailable) { last = unavailable; }
            Thread.sleep(50);
        } while (System.nanoTime() < deadline);
        throw new AssertionError("Dedicated Redis client did not become ready within 15 seconds", last);
    }
    @AfterEach void cleanup() {
        if(redis!=null&&key!=null)redis.delete(key);
        if(connection!=null)connection.destroy();
        UserContext.removeUser();
    }
    String payload(long expiry) {
        return "{\"tenantId\":42,\"username\":\""+username+"\",\"authVersion\":3,\"expiresAt\":"+expiry+"}";
    }
    void seed(String value) {
        redis.opsForHash().put(key,token,value);redis.expire(key,Duration.ofMinutes(30));
    }
    MockHttpServletRequest request() {
        var r=new MockHttpServletRequest("GET","/api/short-link/admin/v1/group");
        r.setRemoteAddr("127.0.0.1");r.setLocalPort(8002);
        r.addHeader("Host","admin.example");r.addHeader("username",username);r.addHeader("token",token);
        r.addHeader("x-shortlink-tenant-id","999");r.addHeader("X-Internal-Token","forged-old-gateway-token");
        return r;
    }
    int execute(MockHttpServletRequest r, boolean expectedReach) throws Exception {
        var response=new MockHttpServletResponse();var reached=new AtomicBoolean();
        filter.doFilter(r,response,(raw,s)->{
            reached.set(true);assertEquals("42",UserContext.getUserId());
            assertNull(((jakarta.servlet.http.HttpServletRequest)raw).getHeader("x-shortlink-tenant-id"));
            assertNull(((jakarta.servlet.http.HttpServletRequest)raw).getHeader("token"));
        });
        assertEquals(expectedReach,reached.get());assertNull(UserContext.getUserId());
        return response.getStatus();
    }
    @Test void realRedisIndividualExpiryCannotPassWhileSharedHashTtlIsPositive() throws Exception {
        seed(payload(System.currentTimeMillis()+30000));
        redis.opsForHash().put(key,"another-valid-session-12345",payload(System.currentTimeMillis()+30000));
        assertEquals(200,execute(request(),true));verify(observedSessions,times(1)).find(username,token);
        seed(payload(1));assertEquals(401,execute(request(),false));
        assertTrue(redis.getExpire(key)>0);
    }
    @Test void issuedSessionSupportsOwnLogoutAndAuthorityRevocation() throws Exception {
        token=sessions.issue(42,username,3);
        assertEquals(200,execute(request(),true));
        account.setAuthVersion(4L);assertEquals(401,execute(request(),false));
        account.setAuthVersion(3L);account.setDisabled(true);assertEquals(401,execute(request(),false));
        account.setDisabled(false);sessions.revoke(username,token);assertEquals(401,execute(request(),false));
    }
    @Test void minimalSessionSchemaRejectsCredentialPayloadDuplicateFieldsAndWrongNumericTypes() throws Exception {
        for(String value:List.of(
                payload(Long.MAX_VALUE).replace("}",",\"passwordHash\":\"forbidden\"}"),
                payload(Long.MAX_VALUE).replace("\"tenantId\":42","\"tenantId\":\"42\""),
                payload(Long.MAX_VALUE).replace("\"tenantId\":42","\"tenantId\":42,\"tenantId\":43"),
                payload(Long.MAX_VALUE)+" null", payload(Long.MAX_VALUE)+" {\"passwordHash\":\"forbidden\"}",
                payload(Long.MAX_VALUE)+" junk",
                "{\"username\":\""+username+"\"}","null","not-json")) {
            seed(value);assertEquals(401,execute(request(),false));
        }
        verifyNoInteractions(users);
    }
    @Test void duplicateHeadersAndUntrustedPeerNeverUseRealSessionStore() throws Exception {
        seed(payload(Long.MAX_VALUE));
        var duplicate=request();duplicate.addHeader("token",token);assertEquals(400,execute(duplicate,false));
        var untrusted=request();untrusted.setRemoteAddr("192.0.2.9");assertEquals(403,execute(untrusted,false));
        verify(observedSessions,never()).find(any(),any());verifyNoInteractions(users);
    }

    @Test void realRedisTransportDisconnectionReturns503AndRecoversWithoutServerMutation() throws Exception {
        seed(payload(Long.MAX_VALUE));
        var properties = ManagementIdentityTest.properties();
        var client = LettuceClientConfiguration.builder();
        new UserConfiguration().boundedAdminRedis(properties).customize(client);
        try (var relay = new RedisRelay(Integer.parseInt(System.getenv("SHORTLINK_REDIS_TEST_PORT")))) {
            var local = new LettuceConnectionFactory(new RedisStandaloneConfiguration("127.0.0.1", relay.port()), client.build());
            local.afterPropertiesSet();
            try {
                var template = new StringRedisTemplate(local); template.afterPropertiesSet();
                filter = new UserTransmitFilter(new TrustedManagementIdentity(users), new RedisAccountSessionStore(template), properties, 8102);
                assertEquals(200, execute(request(), true));
                relay.available(false);
                long start = System.nanoTime();
                assertEquals(503, execute(request(), false));
                assertTrue(System.nanoTime() - start < Duration.ofSeconds(3).toNanos(), "Disconnected lookup must stay bounded");
                relay.available(true);
                long deadline = System.nanoTime() + Duration.ofSeconds(8).toNanos();
                boolean recovered = false;
                while (!recovered && System.nanoTime() < deadline) {
                    var response = new MockHttpServletResponse();
                    var reached = new AtomicBoolean();
                    filter.doFilter(request(), response, (r,s) -> reached.set(true));
                    recovered = reached.get();
                    if (!recovered) { assertEquals(503, response.getStatus()); Thread.sleep(50); }
                }
                assertTrue(recovered, "The same Lettuce client must automatically reconnect");
            } finally { local.destroy(); }
        }
    }

    /** Only this fixture's sockets are interrupted; the dedicated Redis server is never paused/stopped. */
    private static final class RedisRelay implements AutoCloseable {
        private final ServerSocket listener;
        private final int upstreamPort;
        private final java.util.Set<Socket> sockets = ConcurrentHashMap.newKeySet();
        private final ExecutorService io = new ThreadPoolExecutor(4, 4, 0, TimeUnit.SECONDS,
                new ArrayBlockingQueue<>(8), r -> new Thread(r, "admin-redis-it-copy"));
        private final Thread accept;
        private volatile boolean online = true;
        RedisRelay(int upstreamPort) throws IOException {
            this.upstreamPort = upstreamPort;
            listener = new ServerSocket(0, 8, InetAddress.getByName("127.0.0.1"));
            accept = new Thread(this::acceptLoop, "admin-redis-it-accept"); accept.start();
        }
        int port() { return listener.getLocalPort(); }
        void available(boolean value) { online = value; if (!value) sockets.forEach(RedisRelay::closeSocket); }
        private void acceptLoop() {
            while (!listener.isClosed()) {
                Socket incoming = null, upstream = null;
                try {
                    incoming = listener.accept();
                    if (!online) { incoming.close(); continue; }
                    upstream = new Socket("127.0.0.1", upstreamPort);
                    sockets.add(incoming); sockets.add(upstream);
                    Socket front = incoming, back = upstream;
                    io.execute(() -> copy(front, back)); io.execute(() -> copy(back, front));
                } catch (IOException | RejectedExecutionException stopped) {
                    closeSocket(incoming); closeSocket(upstream);
                }
            }
        }
        private void copy(Socket from, Socket to) {
            try { from.getInputStream().transferTo(to.getOutputStream()); }
            catch (IOException disconnected) { /* expected when the fixture interrupts its own transport */ }
            finally { closeSocket(from); closeSocket(to); sockets.remove(from); sockets.remove(to); }
        }
        private static void closeSocket(Socket socket) {
            if (socket != null) try { socket.close(); } catch (IOException ignored) { }
        }
        @Override public void close() throws Exception {
            listener.close(); sockets.forEach(RedisRelay::closeSocket); accept.join(2000);
            io.shutdownNow(); assertTrue(io.awaitTermination(3, TimeUnit.SECONDS));
            assertFalse(accept.isAlive());
        }
    }
}
