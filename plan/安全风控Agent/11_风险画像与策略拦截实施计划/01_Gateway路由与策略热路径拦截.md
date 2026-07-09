# Gateway 路由与策略热路径拦截 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (- [ ]) syntax for tracking.

**Goal:** 交付短链跳转热路径的 gateway 风控拦截能力，确保 gateway 只读 Redis 即可执行 DISABLE_SHORT_LINK、BLOCK_IP、LIMIT_RATE、LIMIT_TIME_WINDOW。

**Architecture:** 本批只修改 gateway 模块。先固化可测试路由基线，再建立 domain/IP/hash/key 工具合同，最后在 RiskPolicyGatewayFilterFactory 中按 Redis 策略拦截。

**Tech Stack:** Java 17、Spring Boot 3.0.7、JUnit 5、MySQL/H2、Redis；按本批涉及模块额外使用 Spring Cloud Gateway、Spring AI Alibaba Graph、MockMvc 或 WebTestClient。

---

## 执行范围

覆盖 Task 1 - Task 3。
本批完成后，后续 agent-service 的 Redis publisher 必须对齐本批 key builder。
不要在 gateway 中引入 MySQL、Feign、RestTemplate、WebClient 或 agent-service 调用。

## 执行前检查

- [ ] `git status --short --branch`，确认是否存在其他批次未提交改动。
- [ ] 如当前已有 Task 3 代码改动，先完成本批测试并单独提交 gateway 文件。
- [ ] 确认 `RISK_HASH_SALT` 不写入任何配置文件，只使用 `${RISK_HASH_SALT:}` 占位。

## 任务明细

### Task 1: Gateway 路由基线与测试依赖

**Files:**

- Modify: `gateway/pom.xml`
- Create: `gateway/src/main/resources/application-risk-local.yaml`
- Create: `gateway/src/test/java/com/nageoffer/shortlink/gateway/config/GatewayRiskRouteConfigTest.java`

- [ ] **Step 1: 给 gateway 增加测试依赖**

在 `gateway/pom.xml` 加入 test 依赖：

```xml
<dependency>
    <groupId>org.springframework.boot</groupId>
    <artifactId>spring-boot-starter-test</artifactId>
    <scope>test</scope>
</dependency>
<dependency>
    <groupId>org.springframework.cloud</groupId>
    <artifactId>spring-cloud-starter-contract-stub-runner</artifactId>
    <scope>test</scope>
</dependency>
```

预期：`mvn -pl gateway test` 可以执行 JUnit 5 测试。

- [ ] **Step 2: 创建本地风险路由配置**

创建 `gateway/src/main/resources/application-risk-local.yaml`：

```yaml
spring:
  cloud:
    gateway:
      routes:
        - id: short-link-admin-api
          uri: lb://short-link-admin
          order: 0
          predicates:
            - Path=/api/short-link/admin/v1/**
          filters:
            - TokenValidate

        - id: short-link-project-api
          uri: lb://short-link-project
          order: 1
          predicates:
            - Path=/api/short-link/v1/**

        - id: short-link-redirect
          uri: lb://short-link-project
          order: 100
          predicates:
            - Path=/{shortUri}
          filters:
            - RiskPolicy

short-link:
  risk:
    policy:
      trusted-proxy-enabled: true
      not-found-mode: status
      hash-salt: ${RISK_HASH_SALT:}
```

- [ ] **Step 3: 写路由绑定测试**

创建 `GatewayRiskRouteConfigTest`，测试只加载路由定义，不启动 Redis：

```java
package com.nageoffer.shortlink.gateway.config;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.cloud.gateway.route.RouteDefinition;
import org.springframework.cloud.gateway.route.RouteDefinitionLocator;
import org.springframework.test.context.ActiveProfiles;
import reactor.core.publisher.Flux;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(properties = {
        "spring.cloud.nacos.discovery.enabled=false",
        "spring.autoconfigure.exclude=org.springframework.boot.autoconfigure.data.redis.RedisAutoConfiguration,"
                + "org.springframework.boot.autoconfigure.data.redis.RedisRepositoriesAutoConfiguration"
})
@ActiveProfiles("risk-local")
class GatewayRiskRouteConfigTest {

    @Autowired
    private RouteDefinitionLocator routeDefinitionLocator;

    @Test
    void redirectRouteHasRiskPolicyFilterAndApiRoutesHaveHigherPriority() {
        List<RouteDefinition> routes = Flux.from(routeDefinitionLocator.getRouteDefinitions()).collectList().block();

        RouteDefinition redirect = find(routes, "short-link-redirect");
        RouteDefinition adminApi = find(routes, "short-link-admin-api");
        RouteDefinition projectApi = find(routes, "short-link-project-api");

        assertThat(redirect.getOrder()).isEqualTo(100);
        assertThat(redirect.getFilters()).anyMatch(filter -> filter.getName().equals("RiskPolicy"));
        assertThat(adminApi.getOrder()).isLessThan(redirect.getOrder());
        assertThat(projectApi.getOrder()).isLessThan(redirect.getOrder());
    }

    private RouteDefinition find(List<RouteDefinition> routes, String id) {
        return routes.stream()
                .filter(route -> route.getId().equals(id))
                .findFirst()
                .orElseThrow();
    }
}
```

- [ ] **Step 4: 运行失败测试**

Run:

```bash
mvn -pl gateway -Dtest=GatewayRiskRouteConfigTest test
```

Expected: FAIL，原因是 `RiskPolicyGatewayFilterFactory` 不存在，路由加载找不到 `RiskPolicy` filter。

- [ ] **Step 5: 临时创建最小 RiskPolicy filter 占位类**

创建 `gateway/src/main/java/com/nageoffer/shortlink/gateway/filter/RiskPolicyGatewayFilterFactory.java`：

```java
package com.nageoffer.shortlink.gateway.filter;

import com.nageoffer.shortlink.gateway.config.Config;
import org.springframework.cloud.gateway.filter.GatewayFilter;
import org.springframework.cloud.gateway.filter.factory.AbstractGatewayFilterFactory;
import org.springframework.stereotype.Component;

@Component
public class RiskPolicyGatewayFilterFactory extends AbstractGatewayFilterFactory<Config> {

    public RiskPolicyGatewayFilterFactory() {
        super(Config.class);
    }

    @Override
    public GatewayFilter apply(Config config) {
        return (exchange, chain) -> chain.filter(exchange);
    }
}
```

- [ ] **Step 6: 运行通过测试**

Run:

```bash
mvn -pl gateway -Dtest=GatewayRiskRouteConfigTest test
```

Expected: PASS。

- [ ] **Step 7: 提交并推送**

```bash
git add gateway/pom.xml gateway/src/main/resources/application-risk-local.yaml gateway/src/test/java/com/nageoffer/shortlink/gateway/config/GatewayRiskRouteConfigTest.java gateway/src/main/java/com/nageoffer/shortlink/gateway/filter/RiskPolicyGatewayFilterFactory.java
git commit -m "feat: add gateway risk route baseline"
git push
```

### Task 2: Gateway 风控工具类

**Files:**

- Create: `gateway/src/main/java/com/nageoffer/shortlink/gateway/risk/RiskPolicyProperties.java`
- Create: `gateway/src/main/java/com/nageoffer/shortlink/gateway/risk/RiskDomainNormalizer.java`
- Create: `gateway/src/main/java/com/nageoffer/shortlink/gateway/risk/RiskClientIpResolver.java`
- Create: `gateway/src/main/java/com/nageoffer/shortlink/gateway/risk/RiskHashService.java`
- Create: `gateway/src/main/java/com/nageoffer/shortlink/gateway/risk/RiskPolicyRedisKeyBuilder.java`
- Create: `gateway/src/main/java/com/nageoffer/shortlink/gateway/risk/RiskPolicyPayload.java`
- Create: `gateway/src/test/java/com/nageoffer/shortlink/gateway/risk/RiskDomainNormalizerTest.java`
- Create: `gateway/src/test/java/com/nageoffer/shortlink/gateway/risk/RiskClientIpResolverTest.java`
- Create: `gateway/src/test/java/com/nageoffer/shortlink/gateway/risk/RiskHashServiceTest.java`
- Create: `gateway/src/test/java/com/nageoffer/shortlink/gateway/risk/RiskPolicyRedisKeyBuilderTest.java`

- [ ] **Step 1: 写 domain 标准化失败测试**

`RiskDomainNormalizerTest`：

```java
package com.nageoffer.shortlink.gateway.risk;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class RiskDomainNormalizerTest {

    private final RiskDomainNormalizer normalizer = new RiskDomainNormalizer();

    @Test
    void removesDefaultPortsAndKeepsNonDefaultPorts() {
        assertThat(normalizer.normalize("nurl.ink")).isEqualTo("nurl.ink");
        assertThat(normalizer.normalize("nurl.ink:80")).isEqualTo("nurl.ink");
        assertThat(normalizer.normalize("nurl.ink:443")).isEqualTo("nurl.ink");
        assertThat(normalizer.normalize("127.0.0.1:8000")).isEqualTo("127.0.0.1:8000");
        assertThat(normalizer.normalize("LOCALHOST:5174")).isEqualTo("localhost:5174");
    }
}
```

- [ ] **Step 2: 写 IP 解析失败测试**

`RiskClientIpResolverTest`：

```java
package com.nageoffer.shortlink.gateway.risk;

import org.junit.jupiter.api.Test;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;

import java.net.InetSocketAddress;

import static org.assertj.core.api.Assertions.assertThat;

class RiskClientIpResolverTest {

    @Test
    void usesForwardedHeadersOnlyWhenTrustedProxyEnabled() {
        MockServerHttpRequest request = MockServerHttpRequest.get("/abc123")
                .remoteAddress(new InetSocketAddress("10.0.0.9", 55000))
                .header("X-Forwarded-For", "203.0.113.8, 10.0.0.1")
                .header("X-Real-IP", "203.0.113.9")
                .build();

        assertThat(new RiskClientIpResolver(true).resolve(request)).isEqualTo("203.0.113.8");
        assertThat(new RiskClientIpResolver(false).resolve(request)).isEqualTo("10.0.0.9");
    }
}
```

- [ ] **Step 3: 写 hash 与 key builder 失败测试**

`RiskHashServiceTest`：

```java
package com.nageoffer.shortlink.gateway.risk;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class RiskHashServiceTest {

    @Test
    void hashesWithSaltAndRejectsBlankSalt() {
        RiskHashService hashService = new RiskHashService("risk-test-salt");

        String hash = hashService.sha256("203.0.113.8");

        assertThat(hash).hasSize(64);
        assertThat(hash).doesNotContain("203.0.113.8");
        assertThat(hashService.sha256("203.0.113.8")).isEqualTo(hash);
        assertThatThrownBy(() -> new RiskHashService(" ").sha256("203.0.113.8"))
                .isInstanceOf(IllegalStateException.class);
    }
}
```

`RiskPolicyRedisKeyBuilderTest`：

```java
package com.nageoffer.shortlink.gateway.risk;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class RiskPolicyRedisKeyBuilderTest {

    private final RiskPolicyRedisKeyBuilder builder = new RiskPolicyRedisKeyBuilder();

    @Test
    void buildsPolicyAndRateKeys() {
        assertThat(builder.disableShortLinkKey("nurl.ink", "abc123"))
                .isEqualTo("risk:policy:short-link:disable:nurl.ink:abc123");
        assertThat(builder.rateLimitShortLinkKey("nurl.ink", "abc123"))
                .isEqualTo("risk:policy:short-link:rate-limit:nurl.ink:abc123");
        assertThat(builder.timeWindowShortLinkKey("nurl.ink", "abc123"))
                .isEqualTo("risk:policy:short-link:time-window:nurl.ink:abc123");
        assertThat(builder.blockIpKey("hash001"))
                .isEqualTo("risk:policy:ip:block:hash001");
        assertThat(builder.rateCounterKey("nurl.ink", "abc123", "hash001"))
                .isEqualTo("risk:rate:nurl.ink:abc123:hash001");
    }
}
```

- [ ] **Step 4: 运行失败测试**

Run:

```bash
mvn -pl gateway -Dtest=RiskDomainNormalizerTest,RiskClientIpResolverTest,RiskHashServiceTest,RiskPolicyRedisKeyBuilderTest test
```

Expected: FAIL，原因是工具类不存在。

- [ ] **Step 5: 创建工具类**

实现约束：

```text
RiskDomainNormalizer.normalize(host)
  - null/blank 返回空字符串
  - 转小写
  - 去掉首尾空白
  - :80 与 :443 去端口
  - 其他端口保留

RiskClientIpResolver.resolve(request)
  - trustedProxyEnabled=true：X-Forwarded-For 第一段、X-Real-IP、remoteAddress
  - trustedProxyEnabled=false：remoteAddress
  - 全部缺失返回 "unknown"

RiskHashService.sha256(value)
  - salt 为空时抛 IllegalStateException
  - 使用 HmacSHA256
  - 输出 64 位小写 hex

RiskPolicyRedisKeyBuilder
  - 固定 key 格式与设计文档一致

RiskPolicyPayload
  - 字段：action、limit、windowSeconds、timezone、allowedWindows、blockedWindows、reason、expireEpochMs
```

- [ ] **Step 6: 运行通过测试**

Run:

```bash
mvn -pl gateway -Dtest=RiskDomainNormalizerTest,RiskClientIpResolverTest,RiskHashServiceTest,RiskPolicyRedisKeyBuilderTest test
```

Expected: PASS。

- [ ] **Step 7: 提交并推送**

```bash
git add gateway/src/main/java/com/nageoffer/shortlink/gateway/risk gateway/src/test/java/com/nageoffer/shortlink/gateway/risk
git commit -m "feat: add gateway risk policy utilities"
git push
```

### Task 3: Gateway RiskPolicy 过滤器真实拦截

**Files:**

- Modify: `gateway/src/main/java/com/nageoffer/shortlink/gateway/filter/RiskPolicyGatewayFilterFactory.java`
- Create: `gateway/src/test/java/com/nageoffer/shortlink/gateway/filter/RiskPolicyGatewayFilterFactoryTest.java`

- [ ] **Step 1: 写 disable/block-ip/rate-limit/time-window 测试**

测试要使用 mock `StringRedisTemplate` 和 mock reactive exchange，不启动真实 Redis。核心断言：

```java
@Test
void disablePolicyReturns404AndDoesNotCallDownstreamChain() {
    when(valueOps.get("risk:policy:short-link:disable:nurl.ink:abc123"))
            .thenReturn("{\"action\":\"DISABLE_SHORT_LINK\"}");

    Mono<Void> result = filter.apply(new Config()).filter(exchange("nurl.ink", "/abc123", "203.0.113.8"), chain);

    StepVerifier.create(result).verifyComplete();
    assertThat(responseStatus()).isEqualTo(HttpStatus.NOT_FOUND);
    verify(chain, never()).filter(any());
}

@Test
void blockIpPolicyReturns403() {
    when(valueOps.get("risk:policy:short-link:disable:nurl.ink:abc123")).thenReturn(null);
    when(valueOps.get("risk:policy:ip:block:" + ipHash)).thenReturn("{\"action\":\"BLOCK_IP\"}");

    StepVerifier.create(filter.apply(new Config()).filter(exchange("nurl.ink", "/abc123", "203.0.113.8"), chain))
            .verifyComplete();

    assertThat(responseStatus()).isEqualTo(HttpStatus.FORBIDDEN);
}

@Test
void rateLimitPolicyReturns429WhenCounterExceedsLimit() {
    when(valueOps.get("risk:policy:short-link:rate-limit:nurl.ink:abc123"))
            .thenReturn("{\"action\":\"LIMIT_RATE\",\"limit\":1,\"windowSeconds\":60}");
    when(valueOps.increment(startsWith("risk:rate:nurl.ink:abc123:"))).thenReturn(2L);

    StepVerifier.create(filter.apply(new Config()).filter(exchange("nurl.ink", "/abc123", "203.0.113.8"), chain))
            .verifyComplete();

    assertThat(responseStatus()).isEqualTo(HttpStatus.TOO_MANY_REQUESTS);
}

@Test
void timeWindowPolicyReturns403OutsideAllowedWindow() {
    when(valueOps.get("risk:policy:short-link:time-window:nurl.ink:abc123"))
            .thenReturn("{\"action\":\"LIMIT_TIME_WINDOW\",\"timezone\":\"Asia/Shanghai\",\"allowedWindows\":[\"09:00-10:00\"]}");

    StepVerifier.create(filter.apply(new Config()).filter(exchangeAt("nurl.ink", "/abc123", "203.0.113.8", "2026-07-10T11:00:00+08:00"), chain))
            .verifyComplete();

    assertThat(responseStatus()).isEqualTo(HttpStatus.FORBIDDEN);
}
```

完整测试类需要补充 helper：

```text
exchange(host, path, ip)
exchangeAt(host, path, ip, now)
responseStatus()
```

使用 `Clock.fixed(Instant.parse("2026-07-10T03:00:00Z"), ZoneId.of("Asia/Shanghai"))` 注入过滤器，避免时间测试不稳定。

- [ ] **Step 2: 运行失败测试**

Run:

```bash
mvn -pl gateway -Dtest=RiskPolicyGatewayFilterFactoryTest test
```

Expected: FAIL，原因是占位 filter 未读取 Redis。

- [ ] **Step 3: 改造 RiskPolicyGatewayFilterFactory 构造函数**

目标构造函数：

```java
public RiskPolicyGatewayFilterFactory(
        StringRedisTemplate stringRedisTemplate,
        RiskPolicyProperties riskPolicyProperties
) {
    super(Config.class);
    this.stringRedisTemplate = stringRedisTemplate;
    this.domainNormalizer = new RiskDomainNormalizer();
    this.ipResolver = new RiskClientIpResolver(riskPolicyProperties.isTrustedProxyEnabled());
    this.hashService = new RiskHashService(riskPolicyProperties.getHashSalt());
    this.keyBuilder = new RiskPolicyRedisKeyBuilder();
    this.clock = Clock.system(ZoneId.of(riskPolicyProperties.getClockZone()));
}
```

给测试保留包级构造函数，允许注入 `Clock`。

- [ ] **Step 4: 实现拦截顺序**

严格顺序：

```text
1. 解析 domain + shortUri。
2. 查 disable short-link key，命中返回 404。
3. 解析 IP 并 hash。
4. 查 block-ip key，命中返回 403。
5. 查 time-window key，窗口外返回 403。
6. 查 rate-limit key，超过阈值返回 429。
7. 未命中策略时 chain.filter(exchange)。
```

响应体使用现有 `GatewayErrorResult`：

```json
{"status":429,"message":"Risk policy rejected request"}
```

- [ ] **Step 5: 运行通过测试**

Run:

```bash
mvn -pl gateway -Dtest=RiskPolicyGatewayFilterFactoryTest,GatewayRiskRouteConfigTest test
```

Expected: PASS。

- [ ] **Step 6: 整个 gateway 模块测试**

Run:

```bash
mvn -pl gateway test
```

Expected: PASS。

- [ ] **Step 7: 提交并推送**

```bash
git add gateway/src/main/java/com/nageoffer/shortlink/gateway/filter/RiskPolicyGatewayFilterFactory.java gateway/src/test/java/com/nageoffer/shortlink/gateway/filter/RiskPolicyGatewayFilterFactoryTest.java
git commit -m "feat: enforce gateway risk policies"
git push
```


## 批次完成验收

Run:

```bash
mvn -pl gateway "-Dtest=GatewayRiskRouteConfigTest,RiskDomainNormalizerTest,RiskClientIpResolverTest,RiskHashServiceTest,RiskPolicyRedisKeyBuilderTest,RiskPolicyGatewayFilterFactoryTest" test
mvn -pl gateway test
git diff --check
```

Expected:

```text
所有 gateway 测试 PASS。
RiskPolicyGatewayFilter 覆盖 404/403/429/放行路径。
敏感 salt 未提交。
提交信息建议：feat: enforce gateway risk policies
```
