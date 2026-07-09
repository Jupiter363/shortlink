# agent-service 风控表结构与策略发布 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (- [ ]) syntax for tracking.

**Goal:** 交付 agent-service 风控 schema、通用模型、策略持久化、动作审计和 Redis 发布能力。

**Architecture:** 本批在 agent-service 内建立 riskcommon 与 riskpolicy 边界；riskpolicy 负责 MySQL 策略账本和 Redis 生效策略，gateway 只消费 Redis。

**Tech Stack:** Java 17、Spring Boot 3.0.7、JUnit 5、MySQL/H2、Redis；按本批涉及模块额外使用 Spring Cloud Gateway、Spring AI Alibaba Graph、MockMvc 或 WebTestClient。

---

## 执行范围

覆盖 Task 4 - Task 5。
本批是后续 risk-profile、risk-center、SecurityRisk Agent 自动 LIMIT_RATE 的基础。

## 执行前检查

- [ ] 确认 11-01 已固定 Redis key 格式。
- [ ] 确认 `agent-service/src/main/resources/sql/agent_service_schema.sql` 当前内容，避免覆盖已有 checkpoint 表。
- [ ] 确认测试使用 H2 或 mock Redis，不依赖本机真实服务。

## 任务明细

### Task 4: agent-service 风控表结构与通用模型

**Files:**

- Modify: `agent-service/src/main/resources/sql/agent_service_schema.sql`
- Modify: `agent-service/src/main/java/com/nageoffer/shortlink/agent/infrastructure/config/AgentProperties.java`
- Modify: `agent-service/src/main/resources/application.yaml`
- Create: `agent-service/src/main/java/com/nageoffer/shortlink/agent/riskcommon/model/RiskLevel.java`
- Create: `agent-service/src/main/java/com/nageoffer/shortlink/agent/riskcommon/model/RiskTargetType.java`
- Create: `agent-service/src/main/java/com/nageoffer/shortlink/agent/riskcommon/model/RiskReasonCode.java`
- Create: `agent-service/src/main/java/com/nageoffer/shortlink/agent/riskcommon/model/RiskPolicyAction.java`
- Create: `agent-service/src/main/java/com/nageoffer/shortlink/agent/riskcommon/model/RiskPolicyStatus.java`
- Create: `agent-service/src/main/java/com/nageoffer/shortlink/agent/riskcommon/model/RiskReviewAction.java`
- Create: `agent-service/src/main/java/com/nageoffer/shortlink/agent/riskcommon/model/RiskWatchStatus.java`
- Create: `agent-service/src/main/java/com/nageoffer/shortlink/agent/riskcommon/model/RiskPolicySource.java`
- Create: `agent-service/src/main/java/com/nageoffer/shortlink/agent/riskcommon/model/RiskEventSource.java`
- Create: `agent-service/src/main/java/com/nageoffer/shortlink/agent/riskcommon/json/RiskJsonCodec.java`
- Create: `agent-service/src/main/java/com/nageoffer/shortlink/agent/riskcommon/safety/RiskHashService.java`
- Create: `agent-service/src/main/java/com/nageoffer/shortlink/agent/riskcommon/safety/RiskSensitiveDataGuard.java`
- Create: `agent-service/src/main/java/com/nageoffer/shortlink/agent/riskcommon/redis/RiskPolicyRedisKeyBuilder.java`
- Create: `agent-service/src/test/java/com/nageoffer/shortlink/agent/riskcommon/RiskCommonModelTest.java`
- Create: `agent-service/src/test/java/com/nageoffer/shortlink/agent/infrastructure/config/AgentRiskPropertiesTest.java`

- [ ] **Step 1: 写 schema 可执行测试**

在 `RiskCommonModelTest` 中执行 schema 并断言风险表存在：

```java
@Test
void riskTablesAreCreatedByAgentServiceSchema() {
    DataSource dataSource = h2DataSource("risk_schema");
    new ResourceDatabasePopulator(new ClassPathResource("sql/agent_service_schema.sql")).execute(dataSource);
    JdbcTemplate jdbcTemplate = new JdbcTemplate(dataSource);

    assertThat(tableExists(jdbcTemplate, "t_agent_risk_event")).isTrue();
    assertThat(tableExists(jdbcTemplate, "t_agent_risk_snapshot")).isTrue();
    assertThat(tableExists(jdbcTemplate, "t_agent_short_link_risk_profile")).isTrue();
    assertThat(tableExists(jdbcTemplate, "t_agent_group_risk_profile")).isTrue();
    assertThat(tableExists(jdbcTemplate, "t_agent_risk_review")).isTrue();
    assertThat(tableExists(jdbcTemplate, "t_agent_risk_policy")).isTrue();
    assertThat(tableExists(jdbcTemplate, "t_agent_risk_action_audit")).isTrue();
}
```

- [ ] **Step 2: 写枚举与配置绑定测试**

断言：

```java
assertThat(RiskLevel.fromScore(0)).isEqualTo(RiskLevel.LOW);
assertThat(RiskLevel.fromScore(39)).isEqualTo(RiskLevel.LOW);
assertThat(RiskLevel.fromScore(40)).isEqualTo(RiskLevel.MEDIUM);
assertThat(RiskLevel.fromScore(69)).isEqualTo(RiskLevel.MEDIUM);
assertThat(RiskLevel.fromScore(70)).isEqualTo(RiskLevel.HIGH);
assertThat(RiskLevel.fromScore(100)).isEqualTo(RiskLevel.HIGH);
assertThat(RiskPolicyAction.DISABLE_SHORT_LINK.requiresManualReview()).isTrue();
assertThat(RiskPolicyAction.LIMIT_RATE.requiresManualReview()).isFalse();
```

配置测试断言：

```java
assertThat(properties.getRisk().getProfile().getBatchIntervalMinutes()).isEqualTo(120);
assertThat(properties.getRisk().getProfile().getActiveScanDays()).isEqualTo(7);
assertThat(properties.getRisk().getHashSalt()).isEmpty();
assertThat(properties.getRisk().getAutoAction().getLimitRateMinScore()).isEqualTo(80);
```

- [ ] **Step 3: 运行失败测试**

Run:

```bash
mvn -pl agent-service -Dtest=RiskCommonModelTest,AgentRiskPropertiesTest test
```

Expected: FAIL，原因是表、枚举、配置不存在。

- [ ] **Step 4: 扩展 SQL schema**

在 `agent_service_schema.sql` 末尾追加 7 张表：

```sql
CREATE TABLE IF NOT EXISTS t_agent_risk_event (
    id BIGINT NOT NULL AUTO_INCREMENT,
    event_id VARCHAR(128) NOT NULL,
    target_type VARCHAR(32) NOT NULL,
    gid VARCHAR(64) NOT NULL DEFAULT '',
    domain VARCHAR(256) NOT NULL DEFAULT '',
    short_uri VARCHAR(128) NOT NULL DEFAULT '',
    full_short_url VARCHAR(512) NOT NULL DEFAULT '',
    risk_score INTEGER NOT NULL,
    risk_level VARCHAR(32) NOT NULL,
    reason_codes_json LONGTEXT NOT NULL,
    evidence_json LONGTEXT NOT NULL,
    recommended_actions_json LONGTEXT NOT NULL,
    agent_summary VARCHAR(2048) NOT NULL DEFAULT '',
    trace_id VARCHAR(128) NOT NULL DEFAULT '',
    session_id VARCHAR(128) NOT NULL DEFAULT '',
    source VARCHAR(64) NOT NULL,
    event_time TIMESTAMP NOT NULL,
    create_time TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    update_time TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (id),
    CONSTRAINT uk_agent_risk_event_event_id UNIQUE (event_id),
    KEY idx_agent_risk_event_gid_time (gid, event_time),
    KEY idx_agent_risk_event_target_time (target_type, domain, short_uri, event_time)
);

CREATE TABLE IF NOT EXISTS t_agent_risk_snapshot (
    id BIGINT NOT NULL AUTO_INCREMENT,
    target_type VARCHAR(32) NOT NULL,
    gid VARCHAR(64) NOT NULL DEFAULT '',
    domain VARCHAR(256) NOT NULL DEFAULT '',
    short_uri VARCHAR(128) NOT NULL DEFAULT '',
    full_short_url VARCHAR(512) NOT NULL DEFAULT '',
    risk_score INTEGER NOT NULL,
    risk_level VARCHAR(32) NOT NULL,
    reason_codes_json LONGTEXT NOT NULL,
    risk_cards_json LONGTEXT NOT NULL,
    watch_status VARCHAR(32) NOT NULL DEFAULT 'NONE',
    policy_status VARCHAR(32) NOT NULL DEFAULT 'NONE',
    last_event_id VARCHAR(128) NOT NULL DEFAULT '',
    last_trace_id VARCHAR(128) NOT NULL DEFAULT '',
    last_scan_time TIMESTAMP NOT NULL,
    create_time TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    update_time TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (id),
    CONSTRAINT uk_agent_risk_snapshot_target UNIQUE (target_type, gid, domain, short_uri),
    KEY idx_agent_risk_snapshot_gid_score (gid, risk_score)
);

CREATE TABLE IF NOT EXISTS t_agent_short_link_risk_profile (
    id BIGINT NOT NULL AUTO_INCREMENT,
    gid VARCHAR(64) NOT NULL,
    domain VARCHAR(256) NOT NULL,
    short_uri VARCHAR(128) NOT NULL,
    full_short_url VARCHAR(512) NOT NULL,
    profile_window_start TIMESTAMP NOT NULL,
    profile_window_end TIMESTAMP NOT NULL,
    pv_2h INTEGER NOT NULL DEFAULT 0,
    uv_2h INTEGER NOT NULL DEFAULT 0,
    pv_24h INTEGER NOT NULL DEFAULT 0,
    uv_24h INTEGER NOT NULL DEFAULT 0,
    pv_7d INTEGER NOT NULL DEFAULT 0,
    uv_7d INTEGER NOT NULL DEFAULT 0,
    pv_growth_2h_vs_24h_avg DECIMAL(12,4),
    top_ip_share DECIMAL(12,4),
    top_visitor_share DECIMAL(12,4),
    top_region_share DECIMAL(12,4),
    top_device_share DECIMAL(12,4),
    top_browser_share DECIMAL(12,4),
    pv_per_uv DECIMAL(12,4),
    peak_hour_share DECIMAL(12,4),
    repeat_visit_ratio DECIMAL(12,4),
    anomaly_score INTEGER NOT NULL DEFAULT 0,
    risk_score INTEGER NOT NULL DEFAULT 0,
    risk_level VARCHAR(32) NOT NULL,
    reason_codes_json LONGTEXT NOT NULL,
    profile_json LONGTEXT NOT NULL,
    create_time TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    update_time TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (id),
    KEY idx_agent_short_link_profile_gid_time (gid, profile_window_end),
    KEY idx_agent_short_link_profile_target_time (domain, short_uri, profile_window_end),
    KEY idx_agent_short_link_profile_gid_score (gid, risk_score)
);

CREATE TABLE IF NOT EXISTS t_agent_group_risk_profile (
    id BIGINT NOT NULL AUTO_INCREMENT,
    gid VARCHAR(64) NOT NULL,
    profile_window_start TIMESTAMP NOT NULL,
    profile_window_end TIMESTAMP NOT NULL,
    total_short_links_scanned INTEGER NOT NULL DEFAULT 0,
    low_risk_count INTEGER NOT NULL DEFAULT 0,
    medium_risk_count INTEGER NOT NULL DEFAULT 0,
    high_risk_count INTEGER NOT NULL DEFAULT 0,
    watching_count INTEGER NOT NULL DEFAULT 0,
    disabled_count INTEGER NOT NULL DEFAULT 0,
    avg_risk_score DECIMAL(12,4) NOT NULL DEFAULT 0,
    max_risk_score INTEGER NOT NULL DEFAULT 0,
    group_risk_score INTEGER NOT NULL DEFAULT 0,
    group_risk_level VARCHAR(32) NOT NULL,
    group_reason_codes_json LONGTEXT NOT NULL,
    top_risk_short_links_json LONGTEXT NOT NULL,
    risk_trend_7d_json LONGTEXT NOT NULL,
    agent_summary VARCHAR(2048) NOT NULL DEFAULT '',
    create_time TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    update_time TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (id),
    KEY idx_agent_group_profile_gid_time (gid, profile_window_end),
    KEY idx_agent_group_profile_gid_score (gid, group_risk_score)
);

CREATE TABLE IF NOT EXISTS t_agent_risk_review (
    id BIGINT NOT NULL AUTO_INCREMENT,
    review_id VARCHAR(128) NOT NULL,
    event_id VARCHAR(128) NOT NULL DEFAULT '',
    target_type VARCHAR(32) NOT NULL,
    gid VARCHAR(64) NOT NULL DEFAULT '',
    domain VARCHAR(256) NOT NULL DEFAULT '',
    short_uri VARCHAR(128) NOT NULL DEFAULT '',
    full_short_url VARCHAR(512) NOT NULL DEFAULT '',
    review_action VARCHAR(64) NOT NULL,
    reviewer VARCHAR(128) NOT NULL,
    review_note VARCHAR(2048) NOT NULL DEFAULT '',
    review_time TIMESTAMP NOT NULL,
    create_time TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    update_time TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (id),
    CONSTRAINT uk_agent_risk_review_review_id UNIQUE (review_id),
    KEY idx_agent_risk_review_event_id (event_id),
    KEY idx_agent_risk_review_gid_time (gid, review_time)
);

CREATE TABLE IF NOT EXISTS t_agent_risk_policy (
    id BIGINT NOT NULL AUTO_INCREMENT,
    policy_id VARCHAR(128) NOT NULL,
    policy_key VARCHAR(512) NOT NULL,
    action VARCHAR(64) NOT NULL,
    target_type VARCHAR(32) NOT NULL,
    gid VARCHAR(64) NOT NULL DEFAULT '',
    domain VARCHAR(256) NOT NULL DEFAULT '',
    short_uri VARCHAR(128) NOT NULL DEFAULT '',
    ip_hash VARCHAR(128) NOT NULL DEFAULT '',
    policy_payload_json LONGTEXT NOT NULL,
    status VARCHAR(32) NOT NULL,
    effective_time TIMESTAMP NOT NULL,
    expire_time TIMESTAMP,
    source VARCHAR(64) NOT NULL,
    trace_id VARCHAR(128) NOT NULL DEFAULT '',
    event_id VARCHAR(128) NOT NULL DEFAULT '',
    create_time TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    update_time TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (id),
    CONSTRAINT uk_agent_risk_policy_policy_id UNIQUE (policy_id),
    KEY idx_agent_risk_policy_key_status (policy_key, status),
    KEY idx_agent_risk_policy_gid_status (gid, status)
);

CREATE TABLE IF NOT EXISTS t_agent_risk_action_audit (
    id BIGINT NOT NULL AUTO_INCREMENT,
    audit_id VARCHAR(128) NOT NULL,
    policy_id VARCHAR(128) NOT NULL DEFAULT '',
    event_id VARCHAR(128) NOT NULL DEFAULT '',
    action VARCHAR(64) NOT NULL,
    executor_type VARCHAR(64) NOT NULL,
    executor VARCHAR(128) NOT NULL,
    reason VARCHAR(2048) NOT NULL DEFAULT '',
    evidence_json LONGTEXT NOT NULL,
    trace_id VARCHAR(128) NOT NULL DEFAULT '',
    create_time TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (id),
    CONSTRAINT uk_agent_risk_action_audit_audit_id UNIQUE (audit_id),
    KEY idx_agent_risk_action_audit_policy_id (policy_id),
    KEY idx_agent_risk_action_audit_event_id (event_id)
);
```

字段以设计文档第 6 节为准。H2 兼容要求：

```text
BIGINT AUTO_INCREMENT
VARCHAR
INTEGER
DECIMAL(10,4)
TIMESTAMP
LONGTEXT
唯一键使用 CONSTRAINT uk_name UNIQUE (column_a, column_b)
```

必须包含索引：

```sql
CONSTRAINT uk_agent_risk_snapshot_target UNIQUE (target_type, gid, domain, short_uri);
CONSTRAINT uk_agent_risk_policy_policy_id UNIQUE (policy_id);
CREATE INDEX idx_agent_risk_event_gid_time ON t_agent_risk_event (gid, event_time);
CREATE INDEX idx_agent_short_link_profile_gid_time ON t_agent_short_link_risk_profile (gid, profile_window_end);
CREATE INDEX idx_agent_group_profile_gid_time ON t_agent_group_risk_profile (gid, profile_window_end);
```

- [ ] **Step 5: 创建通用枚举**

必须包含：

```text
RiskTargetType: GROUP, SHORT_LINK
RiskLevel: LOW, MEDIUM, HIGH，提供 fromScore(int)
RiskReasonCode: TRAFFIC_SPIKE, IP_CONCENTRATION, HIGH_REPEAT_VISIT, PEAK_HOUR_BURST, DEVICE_CONCENTRATION, REGION_CONCENTRATION, BROWSER_CONCENTRATION
RiskPolicyAction: DISABLE_SHORT_LINK, BLOCK_IP, LIMIT_RATE, LIMIT_TIME_WINDOW，提供 requiresManualReview()
RiskPolicyStatus: ACTIVE, DISABLED, EXPIRED
RiskReviewAction: CONFIRM_RISK, FALSE_POSITIVE, IGNORE, WATCH, UNWATCH
RiskWatchStatus: NONE, WATCHING
RiskPolicySource: AGENT_AUTO, MANUAL_REVIEW
RiskEventSource: PROFILE_BATCH, SECURITY_RISK_AGENT, MANUAL_REVIEW
```

- [ ] **Step 6: 扩展 AgentProperties 与 application.yaml**

`AgentProperties` 增加：

```java
private Risk risk = new Risk();

public static class Risk {
    private String hashSalt = "";
    private Profile profile = new Profile();
    private AutoAction autoAction = new AutoAction();
    private Redis redis = new Redis();
}
```

`application.yaml` 增加：

```yaml
short-link:
  agent:
    risk:
      hash-salt: ${RISK_HASH_SALT:}
      profile:
        batch-interval-minutes: ${RISK_PROFILE_BATCH_INTERVAL_MINUTES:120}
        active-scan-days: ${RISK_PROFILE_ACTIVE_SCAN_DAYS:7}
        top-candidate-size: ${RISK_PROFILE_TOP_CANDIDATE_SIZE:10}
      auto-action:
        limit-rate-enabled: ${RISK_AUTO_LIMIT_RATE_ENABLED:true}
        limit-rate-min-score: ${RISK_AUTO_LIMIT_RATE_MIN_SCORE:80}
        limit-rate-limit: ${RISK_AUTO_LIMIT_RATE_LIMIT:60}
        limit-rate-window-seconds: ${RISK_AUTO_LIMIT_RATE_WINDOW_SECONDS:60}
      redis:
        key-prefix: ${RISK_REDIS_KEY_PREFIX:risk}
```

- [ ] **Step 7: 运行通过测试**

Run:

```bash
mvn -pl agent-service -Dtest=RiskCommonModelTest,AgentRiskPropertiesTest test
```

Expected: PASS。

- [ ] **Step 8: 敏感信息扫描**

Run:

```bash
rg -n "sk-[A-Za-z0-9]{16,}|DEEPSEEK_API_KEY\\s*[:=]\\s*sk-|AGENT_INTERNAL_TOKEN\\s*[:=]\\s*sk-|RISK_HASH_SALT\\s*[:=]\\s*[^}\\s]+" agent-service/src/main/resources agent-service/src/main/java agent-service/src/test
```

Expected: no output。

- [ ] **Step 9: 提交并推送**

```bash
git add agent-service/src/main/resources/sql/agent_service_schema.sql agent-service/src/main/resources/application.yaml agent-service/src/main/java/com/nageoffer/shortlink/agent/infrastructure/config/AgentProperties.java agent-service/src/main/java/com/nageoffer/shortlink/agent/riskcommon agent-service/src/test/java/com/nageoffer/shortlink/agent/riskcommon agent-service/src/test/java/com/nageoffer/shortlink/agent/infrastructure/config/AgentRiskPropertiesTest.java
git commit -m "feat: add risk schema and common models"
git push
```

### Task 5: 风控策略落库、审计与 Redis 发布

**Files:**

- Create: `agent-service/src/main/java/com/nageoffer/shortlink/agent/riskpolicy/model/RiskPolicy.java`
- Create: `agent-service/src/main/java/com/nageoffer/shortlink/agent/riskpolicy/model/RiskPolicyActivationCommand.java`
- Create: `agent-service/src/main/java/com/nageoffer/shortlink/agent/riskpolicy/model/RiskPolicyDisableCommand.java`
- Create: `agent-service/src/main/java/com/nageoffer/shortlink/agent/riskpolicy/model/RiskPolicyPayload.java`
- Create: `agent-service/src/main/java/com/nageoffer/shortlink/agent/riskpolicy/repository/JdbcRiskPolicyRepository.java`
- Create: `agent-service/src/main/java/com/nageoffer/shortlink/agent/riskpolicy/repository/JdbcRiskActionAuditRepository.java`
- Create: `agent-service/src/main/java/com/nageoffer/shortlink/agent/riskpolicy/service/RiskPolicyService.java`
- Create: `agent-service/src/main/java/com/nageoffer/shortlink/agent/riskpolicy/service/RiskPolicyRedisPublisher.java`
- Create: `agent-service/src/test/java/com/nageoffer/shortlink/agent/riskpolicy/RiskPolicyRepositoryTest.java`
- Create: `agent-service/src/test/java/com/nageoffer/shortlink/agent/riskpolicy/RiskPolicyServiceTest.java`

- [ ] **Step 1: 写 repository 失败测试**

断言策略 upsert 和审计：

```java
@Test
void upsertsActivePolicyAndWritesAudit() {
    JdbcRiskPolicyRepository policyRepository = repository();
    JdbcRiskActionAuditRepository auditRepository = auditRepository();

    RiskPolicy policy = RiskPolicy.shortLinkPolicy(
            "policy-001",
            "risk:policy:short-link:disable:nurl.ink:abc123",
            RiskPolicyAction.DISABLE_SHORT_LINK,
            "gid-001",
            "nurl.ink",
            "abc123",
            "{\"action\":\"DISABLE_SHORT_LINK\"}",
            RiskPolicySource.MANUAL_REVIEW,
            "trace-001",
            "event-001"
    );

    policyRepository.saveActive(policy);
    auditRepository.saveActivationAudit(policy, "manual-user", "confirmed high risk");

    assertThat(policyRepository.findByPolicyId("policy-001")).isPresent();
    assertThat(policyRepository.findActiveByPolicyKey(policy.policyKey())).isPresent();
    assertThat(auditRepository.countByPolicyId("policy-001")).isEqualTo(1);
}
```

- [ ] **Step 2: 写 service + Redis publisher 失败测试**

使用 mock `StringRedisTemplate`，断言：

```text
activatePolicy(DISABLE_SHORT_LINK)
  - 保存 ACTIVE 策略
  - opsForValue().set(policyKey, payload)
  - 写审计

disablePolicy(policyId)
  - 策略状态改 DISABLED
  - Redis delete(policyKey)
  - 写撤销审计

activatePolicy(BLOCK_IP)
  - hashSalt 为空时抛 IllegalStateException
```

- [ ] **Step 3: 运行失败测试**

Run:

```bash
mvn -pl agent-service -Dtest=RiskPolicyRepositoryTest,RiskPolicyServiceTest test
```

Expected: FAIL，原因是 riskpolicy 包不存在。

- [ ] **Step 4: 实现 repository**

`JdbcRiskPolicyRepository` 必须提供：

```java
public void saveActive(RiskPolicy policy)
public Optional<RiskPolicy> findByPolicyId(String policyId)
public Optional<RiskPolicy> findActiveByPolicyKey(String policyKey)
public void disable(String policyId, String traceId)
```

`JdbcRiskActionAuditRepository` 必须提供：

```java
public void saveActivationAudit(RiskPolicy policy, String executor, String reason)
public void saveDisableAudit(RiskPolicy policy, String executor, String reason)
public int countByPolicyId(String policyId)
```

- [ ] **Step 5: 实现 RiskPolicyRedisPublisher**

```text
publish(policy)
  - 无 expireTime：opsForValue().set(key, payload)
  - 有 expireTime：计算 Duration，Duration > 0 时 set(key, payload, duration)
  - 过期时间已过：不写 Redis，把策略标记为 EXPIRED 由 service 处理

revoke(policy)
  - delete(policyKey)
```

- [ ] **Step 6: 实现 RiskPolicyService**

公开方法：

```java
public RiskPolicy activatePolicy(RiskPolicyActivationCommand command)
public void disablePolicy(RiskPolicyDisableCommand command)
public boolean canAutoLimitRate(RiskLevel level, int score, Set<RiskReasonCode> reasonCodes)
```

`canAutoLimitRate` 规则：

```text
riskLevel = HIGH
riskScore >= 80
强 reasonCode 至少两个：
  TRAFFIC_SPIKE
  IP_CONCENTRATION
  HIGH_REPEAT_VISIT
  PEAK_HOUR_BURST
```

- [ ] **Step 7: 运行通过测试**

Run:

```bash
mvn -pl agent-service -Dtest=RiskPolicyRepositoryTest,RiskPolicyServiceTest test
```

Expected: PASS。

- [ ] **Step 8: 提交并推送**

```bash
git add agent-service/src/main/java/com/nageoffer/shortlink/agent/riskpolicy agent-service/src/test/java/com/nageoffer/shortlink/agent/riskpolicy
git commit -m "feat: add risk policy persistence and redis publisher"
git push
```


## 批次完成验收

Run:

```bash
mvn -pl agent-service "-Dtest=RiskCommonModelTest,RiskPolicyRepositoryTest,RiskPolicyServiceTest,RiskPolicyRedisPublisherTest" test
git diff --check
```

Expected:

```text
schema 可被 H2 测试加载。
策略 ACTIVE/DISABLED/EXPIRED 状态可落库。
Redis publish/revoke key 与 gateway key builder 完全一致。
无真实 RISK_HASH_SALT 或 DeepSeek key 出现在 diff 中。
```
