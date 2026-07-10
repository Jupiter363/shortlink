# 02C 有效策略槽位与 Redis Outbox Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 将风险策略改造成 MySQL 事务事实源，通过单一有效槽位和租约 Outbox 最终同步 Redis，并在该能力通过验证后注册三类风控动作 executor。

**Architecture:** `t_agent_risk_policy` 保存历史，`t_agent_risk_policy_effective` 保存每个 policyKey 的唯一期望版本，`t_agent_risk_policy_sync_outbox` 保存 Redis 命令。策略服务事务内写 history/effective/review/audit/outbox，事务外 worker 执行版本保护的 Redis UPSERT/DELETE。

**Tech Stack:** Spring JDBC、Spring TransactionTemplate、MySQL/H2、Spring Data Redis、Redis Lua、Spring Scheduling、JUnit 5、Mockito。

---

## Task 1: 增强策略历史模型和 Repository

**Files:**

- Modify: `agent-service/src/main/resources/sql/agent_service_schema.sql`
- Create: `agent-service/src/main/resources/sql/migration/V20260712__risk_policy_history_constraints_and_backfill.sql`
- Modify: `agent-service/src/test/java/com/nageoffer/shortlink/agent/migration/AgentPendingActionPolicyConsistencyMigrationTest.java`
- Modify: `agent-service/src/main/java/com/nageoffer/shortlink/agent/riskcommon/model/RiskPolicyStatus.java`
- Modify: `agent-service/src/main/java/com/nageoffer/shortlink/agent/riskpolicy/model/RiskPolicy.java`
- Modify: `agent-service/src/main/java/com/nageoffer/shortlink/agent/riskpolicy/model/RiskPolicyActivationCommand.java`
- Modify: `agent-service/src/main/java/com/nageoffer/shortlink/agent/riskpolicy/repository/JdbcRiskPolicyRepository.java`
- Modify: `agent-service/src/test/java/com/nageoffer/shortlink/agent/riskpolicy/RiskPolicyRepositoryTest.java`

- [ ] **Step 1: 写历史幂等和版本测试**

```java
@Test
void savesPolicyHistoryByIdempotencyKeyAndVersion() {
    Fixture fixture = fixture("risk_policy_history_version");
    RiskPolicy first = policy("policy-1", "manual:action-1", "key-1", 1L, RiskPolicyStatus.ACTIVE);
    RiskPolicy second = policy("policy-2", "manual:action-2", "key-1", 2L, RiskPolicyStatus.ACTIVE);

    fixture.repository.insert(first);
    fixture.repository.markSuperseded("policy-1", "trace-2");
    fixture.repository.insert(second);

    assertThat(fixture.repository.findByIdempotencyKey("manual:action-1"))
            .isPresent().get().extracting(RiskPolicy::status)
            .isEqualTo(RiskPolicyStatus.SUPERSEDED);
    assertThat(fixture.repository.findByPolicyKeyOrderByVersion("key-1"))
            .extracting(RiskPolicy::policyVersion)
            .containsExactly(2L, 1L);
}
```

补充测试：相同 `idempotency_key` 第二次 insert 失败；不同 policyId 不得覆盖历史记录。

增加发布窗口兼容测试：在只执行 V20260711 的 schema 中插入 `idempotency_key=NULL, policy_version=NULL` 的 legacy 行，新 Repository 读取时不得抛空指针，临时投影为 `idempotencyKey=legacy:{policyId}`、`policyVersion=0`；所有新 insert 仍强制传非空 key 和正版本。V20260712 完成后数据测试必须证明不再存在 0/null。

同一步先扩展迁移合同，锁定二段迁移顺序：

```java
@Test
void constraintMigrationBackfillsBeforeTighteningHistoryColumns() throws IOException {
    String migration = read("sql/migration/V20260712__risk_policy_history_constraints_and_backfill.sql");

    assertThat(migration)
            .contains("PAUSE risk-profile scheduler, risk-analysis worker, and policy writers")
            .contains("SET idempotency_key = CONCAT('legacy:', policy_id)")
            .contains("ROW_NUMBER() OVER")
            .contains("INSERT INTO t_agent_risk_policy_effective")
            .contains("INSERT INTO t_agent_risk_policy_sync_outbox")
            .contains("MODIFY COLUMN idempotency_key VARCHAR(512) NOT NULL")
            .contains("CONSTRAINT uk_agent_risk_policy_idempotency UNIQUE (idempotency_key)");
    assertThat(position(migration, "SET idempotency_key = CONCAT('legacy:'"))
            .isLessThan(position(migration, "MODIFY COLUMN idempotency_key"));
}
```

- [ ] **Step 2: 运行测试并确认 RED**

```powershell
mvn -q -pl agent-service -Dtest=RiskPolicyRepositoryTest,AgentPendingActionPolicyConsistencyMigrationTest test
```

Expected: FAIL，当前模型没有 idempotencyKey、policyVersion 和 SUPERSEDED，第二段迁移也尚不存在。

- [ ] **Step 3: 修改模型**

`RiskPolicyStatus`：

```java
public enum RiskPolicyStatus {
    ACTIVE,
    SUPERSEDED,
    DISABLED,
    EXPIRED
}
```

`RiskPolicy` 增加：

```text
idempotencyKey
policyVersion
```

所有静态 factory 不再调用 `LocalDateTime.now()`，由 service 传入明确 `effectiveTime`，保证测试和幂等稳定。

Repository mapper 在 V20260712 前的短暂发布窗口兼容 nullable legacy 行；该 fallback 只用于读取，禁止把 version 0 写回数据库或创建 effective/outbox。

`RiskPolicyActivationCommand` 增加：

```text
idempotencyKey
ipHash
```

删除 rawIp 持久执行路径；BLOCK_IP 使用已经计算的 `ipHash`。

本 Task 同步把 baseline 的 `idempotency_key`、`policy_version` 改为 `NOT NULL` 并加入 `uk_agent_risk_policy_idempotency`。`V20260712` 必须先回填 legacy key 和连续版本、初始化 effective/outbox、执行可运行的空值与重复检查，最后才收紧非空和唯一约束。发布时先暂停写入口并把所有 agent-service 实例升级为兼容 nullable legacy 行的新 Repository，再执行 V20260712；迁移后禁止回滚到旧 `saveActive()` writer，只允许前滚修复。

- [ ] **Step 4: 将 saveActive 拆成历史操作**

Repository 提供：

```java
void insert(RiskPolicy policy);
Optional<RiskPolicy> findByPolicyId(String policyId);
Optional<RiskPolicy> findByIdempotencyKey(String idempotencyKey);
List<RiskPolicy> findByPolicyKeyOrderByVersion(String policyKey);
void markSuperseded(String policyId, String traceId);
void markDisabled(String policyId, String traceId);
void markExpired(String policyId, String traceId);
```

禁止继续使用“update by policyId, absent then insert”的 upsert，因为历史记录必须不可被新 payload 静默改写。

- [ ] **Step 5: 运行测试并提交**

```powershell
mvn -q -pl agent-service -Dtest=RiskPolicyRepositoryTest,RiskCommonModelTest,AgentPendingActionPolicyConsistencyMigrationTest test
git add agent-service/src/main/resources/sql agent-service/src/main/java/com/nageoffer/shortlink/agent/riskcommon agent-service/src/main/java/com/nageoffer/shortlink/agent/riskpolicy agent-service/src/test/java/com/nageoffer/shortlink/agent/riskpolicy agent-service/src/test/java/com/nageoffer/shortlink/agent/riskcommon agent-service/src/test/java/com/nageoffer/shortlink/agent/migration/AgentPendingActionPolicyConsistencyMigrationTest.java
git commit -m "refactor: version risk policy history"
```

## Task 2: 实现 Effective Policy Slot

**Files:**

- Create: `agent-service/src/main/java/com/nageoffer/shortlink/agent/riskpolicy/model/EffectiveRiskPolicy.java`
- Create: `agent-service/src/main/java/com/nageoffer/shortlink/agent/riskpolicy/model/RiskPolicyDesiredState.java`
- Create: `agent-service/src/main/java/com/nageoffer/shortlink/agent/riskpolicy/model/RiskPolicySyncStatus.java`
- Create: `agent-service/src/main/java/com/nageoffer/shortlink/agent/riskpolicy/repository/JdbcEffectiveRiskPolicyRepository.java`
- Create: `agent-service/src/test/java/com/nageoffer/shortlink/agent/riskpolicy/EffectiveRiskPolicyRepositoryTest.java`

- [ ] **Step 1: 写唯一槽位和锁定测试**

```java
@Test
void upsertsOneEffectiveSlotAndIncrementsVersion() {
    Fixture fixture = fixture("effective_policy_slot");
    fixture.repository.upsert(activeSlot("key-1", "policy-1", 1L));
    fixture.repository.upsert(activeSlot("key-1", "policy-2", 2L));

    assertThat(fixture.repository.findByPolicyKey("key-1"))
            .isPresent().get().satisfies(slot -> {
                assertThat(slot.policyId()).isEqualTo("policy-2");
                assertThat(slot.policyVersion()).isEqualTo(2L);
            });
    assertThat(fixture.countSlots("key-1")).isEqualTo(1L);
}
```

测试 `findByPolicyKeyForUpdate` 在同一事务中返回当前槽位。

- [ ] **Step 2: 运行测试并确认 RED**

```powershell
mvn -q -pl agent-service -Dtest=EffectiveRiskPolicyRepositoryTest test
```

Expected: FAIL。

- [ ] **Step 3: 实现模型**

```java
public enum RiskPolicyDesiredState {
    ACTIVE,
    DISABLED,
    EXPIRED
}

public enum RiskPolicySyncStatus {
    PENDING,
    SYNCED,
    RETRY_WAIT,
    DEAD
}
```

`EffectiveRiskPolicy` 字段必须与详细设计第 10.2 节一致。

- [ ] **Step 4: 实现 Repository**

```java
Optional<EffectiveRiskPolicy> findByPolicyKey(String policyKey);
Optional<EffectiveRiskPolicy> findByPolicyKeyForUpdate(String policyKey);
void upsert(EffectiveRiskPolicy policy);
boolean updateSyncStatusIfVersion(
        String policyKey,
        String policyId,
        long policyVersion,
        RiskPolicySyncStatus syncStatus,
        String outboxId,
        String traceId
);
```

H2/MySQL 兼容 upsert 使用“先 update，后 insert，DuplicateKey 后重试 update”，不要在核心逻辑中分支数据库类型。

- [ ] **Step 5: 运行测试并提交**

```powershell
mvn -q -pl agent-service -Dtest=EffectiveRiskPolicyRepositoryTest test
git add agent-service/src/main/java/com/nageoffer/shortlink/agent/riskpolicy agent-service/src/test/java/com/nageoffer/shortlink/agent/riskpolicy/EffectiveRiskPolicyRepositoryTest.java
git commit -m "feat: add effective risk policy slots"
```

## Task 3: 实现 Outbox 模型和 Repository 租约

**Files:**

- Create: `agent-service/src/main/java/com/nageoffer/shortlink/agent/riskpolicy/outbox/RiskPolicySyncOperation.java`
- Create: `agent-service/src/main/java/com/nageoffer/shortlink/agent/riskpolicy/outbox/RiskPolicySyncOutboxStatus.java`
- Create: `agent-service/src/main/java/com/nageoffer/shortlink/agent/riskpolicy/outbox/RiskPolicySyncOutbox.java`
- Create: `agent-service/src/main/java/com/nageoffer/shortlink/agent/riskpolicy/outbox/JdbcRiskPolicySyncOutboxRepository.java`
- Create: `agent-service/src/test/java/com/nageoffer/shortlink/agent/riskpolicy/RiskPolicySyncOutboxRepositoryTest.java`
- Create: `agent-service/src/test/java/com/nageoffer/shortlink/agent/riskpolicy/RiskPolicySyncOutboxConcurrencyTest.java`

- [ ] **Step 1: 写创建幂等和 claim 测试**

```java
@Test
void createsOneOutboxPerPolicyVersionAndOperation() {
    Fixture fixture = fixture("policy_outbox_unique");
    RiskPolicySyncOutbox outbox = pendingOutbox("outbox-1", "key-1", "policy-1", 1L);

    assertThat(fixture.repository.createIfAbsent(outbox)).isTrue();
    assertThat(fixture.repository.createIfAbsent(outbox.withOutboxId("outbox-2"))).isFalse();
    assertThat(fixture.countRows()).isEqualTo(1L);
}

@Test
void claimsPendingAndDueRetryWithLease() {
    Fixture fixture = fixture("policy_outbox_claim");
    fixture.repository.createIfAbsent(pendingOutbox("outbox-1", "key-1", "policy-1", 1L));

    assertThat(fixture.repository.claimNext("worker-1", NOW, Duration.ofMinutes(5), 10))
            .isPresent().get().satisfies(value -> {
                assertThat(value.status()).isEqualTo(RiskPolicySyncOutboxStatus.PROCESSING);
                assertThat(value.ownerToken()).isEqualTo("worker-1");
                assertThat(value.attemptCount()).isEqualTo(1);
            });
}
```

- [ ] **Step 2: 写并发和 stale owner 测试**

复用 `RiskAnalysisJobRepositoryTest` 的 ExecutorService + CountDownLatch 模式，断言两个 worker 只能有一个 claim 成功；错误 ownerToken 不能完成或失败任务。

- [ ] **Step 3: 运行测试并确认 RED**

```powershell
mvn -q -pl agent-service -Dtest=RiskPolicySyncOutboxRepositoryTest,RiskPolicySyncOutboxConcurrencyTest test
```

Expected: FAIL。

- [ ] **Step 4: 实现状态和 Repository**

```java
public enum RiskPolicySyncOutboxStatus {
    PENDING,
    PROCESSING,
    RETRY_WAIT,
    SUCCEEDED,
    SKIPPED,
    DEAD
}
```

Repository 方法：

```java
boolean createIfAbsent(RiskPolicySyncOutbox outbox);
Optional<RiskPolicySyncOutbox> findByOutboxId(String outboxId);
Optional<RiskPolicySyncOutbox> findByOutboxIdForUpdate(String outboxId);
Optional<RiskPolicySyncOutbox> findByPolicyKeyVersionAndOperation(
        String policyKey,
        long policyVersion,
        RiskPolicySyncOperation operation
);
Optional<RiskPolicySyncOutbox> claimNext(String ownerToken, LocalDateTime now, Duration lease, int maxAttempts);
boolean markSucceeded(String outboxId, String ownerToken, LocalDateTime now);
boolean markSkipped(String outboxId, String ownerToken, String reason, LocalDateTime now);
boolean recordFailure(String outboxId, String ownerToken, int maxAttempts, LocalDateTime now, LocalDateTime nextRetry, String error);
boolean resetForReplay(String outboxId, LocalDateTime now);
int recoverExpiredProcessing(LocalDateTime now, int maxAttempts);
```

- [ ] **Step 5: 运行测试并提交**

```powershell
mvn -q -pl agent-service -Dtest=RiskPolicySyncOutboxRepositoryTest,RiskPolicySyncOutboxConcurrencyTest test
git add agent-service/src/main/java/com/nageoffer/shortlink/agent/riskpolicy/outbox agent-service/src/test/java/com/nageoffer/shortlink/agent/riskpolicy/RiskPolicySyncOutboxRepositoryTest.java agent-service/src/test/java/com/nageoffer/shortlink/agent/riskpolicy/RiskPolicySyncOutboxConcurrencyTest.java
git commit -m "feat: add risk policy sync outbox"
```

## Task 4: 实现策略 Redis Value Codec 和版本保护 Publisher

**Files:**

- Create: `agent-service/src/main/resources/lua/risk_policy_compare_and_delete.lua`
- Create: `agent-service/src/main/java/com/nageoffer/shortlink/agent/riskpolicy/service/RiskPolicyDeleteResult.java`
- Create: `agent-service/src/main/java/com/nageoffer/shortlink/agent/riskpolicy/service/RiskPolicyRedisValueCodec.java`
- Modify: `agent-service/src/main/java/com/nageoffer/shortlink/agent/riskpolicy/service/RiskPolicyRedisPublisher.java`
- Modify: `agent-service/src/test/java/com/nageoffer/shortlink/agent/riskpolicy/RiskPolicyRedisPublisherTest.java`
- Create: `agent-service/src/test/java/com/nageoffer/shortlink/agent/riskpolicy/RiskPolicyRedisValueCodecTest.java`

- [ ] **Step 1: 写 value codec 测试**

```java
@Test
void enrichesExistingPayloadWithPolicyMetadata() {
    RiskPolicyRedisValueCodec codec = new RiskPolicyRedisValueCodec(new ObjectMapper());

    String value = codec.encode(
            "policy-1",
            3L,
            "{\"action\":\"LIMIT_RATE\",\"limit\":30,\"windowSeconds\":60}"
    );

    assertThatJson(value).isEqualTo("""
            {
              "action":"LIMIT_RATE",
              "limit":30,
              "policyId":"policy-1",
              "policyVersion":3,
              "windowSeconds":60
            }
            """);
}
```

若项目没有 JSON AssertJ 扩展，使用 `ObjectMapper.readValue(value, Map.class)` 后逐字段断言，不新增只为测试服务的依赖。

- [ ] **Step 2: 写 compare-and-delete 测试**

```java
@Test
void deleteMapsLuaResultsWithoutBlindDelete() {
    StringRedisTemplate redis = mock(StringRedisTemplate.class);
    RiskPolicyRedisPublisher publisher = new RiskPolicyRedisPublisher(redis, CLOCK);
    when(redis.execute(any(DefaultRedisScript.class), eq(List.of("risk:key")), eq("expected-value")))
            .thenReturn(1L);

    assertThat(publisher.compareAndDelete("risk:key", "expected-value"))
            .isEqualTo(RiskPolicyDeleteResult.DELETED);
    verify(redis, never()).delete("risk:key");
}
```

补充返回 `0L -> ALREADY_ABSENT`、`-1L -> VALUE_MISMATCH`，以及 Lua resource 缺失时启动失败的测试。

- [ ] **Step 3: 运行测试并确认 RED**

```powershell
mvn -q -pl agent-service -Dtest=RiskPolicyRedisValueCodecTest,RiskPolicyRedisPublisherTest test
```

Expected: FAIL。

- [ ] **Step 4: 实现 codec**

使用 Jackson 将原 payload 转为 `LinkedHashMap`，覆盖写入 `policyId` 和 `policyVersion`，再按稳定 key 顺序序列化。禁止字符串拼接 JSON。

- [ ] **Step 5: 实现 Publisher**

```java
boolean publish(String policyKey, String redisValueJson, LocalDateTime expireTime);
RiskPolicyDeleteResult compareAndDelete(String policyKey, String expectedRedisValue);
```

`risk_policy_compare_and_delete.lua` 的完整返回合同：

```lua
local current = redis.call('get', KEYS[1])
if not current then
    return 0
end
if current ~= ARGV[1] then
    return -1
end
redis.call('del', KEYS[1])
return 1
```

```java
public enum RiskPolicyDeleteResult {
    DELETED,
    ALREADY_ABSENT,
    VALUE_MISMATCH
}
```

删除旧的 `revoke(RiskPolicy)` 盲删实现。

- [ ] **Step 6: 运行测试并提交**

```powershell
mvn -q -pl agent-service -Dtest=RiskPolicyRedisValueCodecTest,RiskPolicyRedisPublisherTest test
git add agent-service/src/main/resources/lua/risk_policy_compare_and_delete.lua agent-service/src/main/java/com/nageoffer/shortlink/agent/riskpolicy/service agent-service/src/test/java/com/nageoffer/shortlink/agent/riskpolicy
git commit -m "feat: protect versioned redis policies"
```

## Task 5: 将策略激活和禁用改为事务写 history/effective/audit/outbox

**Files:**

- Modify: `agent-service/src/main/java/com/nageoffer/shortlink/agent/riskpolicy/service/RiskPolicyService.java`
- Modify: `agent-service/src/main/java/com/nageoffer/shortlink/agent/riskpolicy/action/RiskPolicyActionPort.java`
- Modify: `agent-service/src/main/java/com/nageoffer/shortlink/agent/riskpolicy/repository/JdbcRiskActionAuditRepository.java`
- Modify: `agent-service/src/main/java/com/nageoffer/shortlink/agent/riskcenter/repository/JdbcRiskReviewRepository.java`
- Modify: `agent-service/src/test/java/com/nageoffer/shortlink/agent/riskpolicy/RiskPolicyServiceTest.java`
- Create: `agent-service/src/test/java/com/nageoffer/shortlink/agent/riskpolicy/RiskPolicyTransactionRollbackTest.java`
- Modify: `agent-service/src/test/java/com/nageoffer/shortlink/agent/infrastructure/config/SpringBeanConstructorContractTest.java`

- [ ] **Step 1: 写事务成功测试**

```java
@Test
void confirmedActionWritesReviewPolicyEffectiveAuditAndOutboxInOneTransaction() {
    Fixture fixture = fixture("policy_transaction_success");

    RiskPolicyConfirmedActionResult result = fixture.service.execute(confirmedDisableCommand("action-1"));

    assertThat(result.policyId()).isEqualTo(deterministicPolicyId("action-1"));
    assertThat(fixture.reviewRepository.countByEventId("event-1")).isEqualTo(1L);
    assertThat(fixture.policyRepository.findByIdempotencyKey("manual:action-1")).isPresent();
    assertThat(fixture.effectiveRepository.findByPolicyKey(result.policyKey())).isPresent();
    assertThat(fixture.auditRepository.countByPolicyId(result.policyId())).isEqualTo(1);
    assertThat(fixture.outboxRepository.findByPolicyKeyVersionAndOperation(
            result.policyKey(), 1L, RiskPolicySyncOperation.UPSERT
    )).isPresent();
}
```

- [ ] **Step 2: 写 rollback 测试**

在 outbox repository 的测试替身中抛出异常，断言 review、policy、effective 和 audit 行数均为 0。测试必须使用真实 `DataSourceTransactionManager` 和 `TransactionTemplate`，不能只验证 mock 调用。

- [ ] **Step 3: 写幂等和替换测试**

```text
相同 manual:actionId 第二次返回原策略，不新增 review/audit/outbox。
同 policyKey 新 action 生成 version 2，version 1 history -> SUPERSEDED。
effective slot 指向 version 2。
禁用历史旧 policyId 返回 POLICY_NOT_EFFECTIVE。
禁用当前 policyId 创建 version 3 DELETE outbox。
```

- [ ] **Step 4: 运行测试并确认 RED**

```powershell
mvn -q -pl agent-service -Dtest=RiskPolicyServiceTest,RiskPolicyTransactionRollbackTest test
```

Expected: FAIL，现有 service 仍同步 Redis。

- [ ] **Step 5: 重构 RiskPolicyService**

构造器注入：

```text
JdbcRiskPolicyRepository
JdbcEffectiveRiskPolicyRepository
JdbcRiskActionAuditRepository
JdbcRiskReviewRepository
JdbcRiskPolicySyncOutboxRepository
RiskPolicyRedisValueCodec
AgentProperties
TransactionTemplate
Clock
```

`RiskPolicyService` 实现 `RiskPolicyActionPort`。公开方法：

```java
RiskPolicy activatePolicy(RiskPolicyActivationCommand command);
RiskPolicyConfirmedActionResult execute(RiskPolicyConfirmedActionCommand command);
void disablePolicy(RiskPolicyDisableCommand command);
boolean canAutoLimitRate(RiskLevel level, int score, Set<RiskReasonCode> reasons);
```

所有写方法通过 `transactionTemplate.execute(...)` 完成。事务中不得引用 `StringRedisTemplate` 或 `RiskPolicyRedisPublisher`。

如为固定 `Clock` 或精简 fixture 保留测试构造器，完整生产构造器必须是唯一 `@Autowired` 构造器；不能等到 Task 8 才修复 Spring 装配合同。

- [ ] **Step 6: 更新自动和人工命令**

人工命令写 `RiskReview(CONFIRM_RISK)`；自动 LIMIT_RATE 不写人工 review，但仍写 policy audit 和 UPSERT outbox。

`RiskPolicyActionExecutor` 把 `RiskPolicyConfirmedActionResult` 的 `policyId/policyKey/policyVersion/policyStatus/syncStatus` 全部复制到 `AgentActionExecutionResult.result`，不得遗漏 policyKey 或用 `toString()` 拼装 Map。

BLOCK_IP policyKey 使用：

```text
risk:policy:short-link:block-ip:{domain}:{shortUri}:{ipHash}
```

- [ ] **Step 7: 运行事务测试并提交**

```powershell
mvn -q -pl agent-service -Dtest=RiskPolicyServiceTest,RiskPolicyTransactionRollbackTest,RiskPolicyRepositoryTest,EffectiveRiskPolicyRepositoryTest,SpringBeanConstructorContractTest test
git add agent-service/src/main/java/com/nageoffer/shortlink/agent/riskpolicy agent-service/src/main/java/com/nageoffer/shortlink/agent/riskcenter/repository/JdbcRiskReviewRepository.java agent-service/src/test/java/com/nageoffer/shortlink/agent/riskpolicy agent-service/src/test/java/com/nageoffer/shortlink/agent/infrastructure/config/SpringBeanConstructorContractTest.java
git commit -m "feat: transact risk policy state changes"
```

## Task 6: 实现 Outbox Worker、调度和 replay API

**Files:**

- Modify: `agent-service/src/main/java/com/nageoffer/shortlink/agent/infrastructure/config/AgentProperties.java`
- Modify: `agent-service/src/test/java/com/nageoffer/shortlink/agent/infrastructure/config/AgentRiskPropertiesTest.java`
- Create: `agent-service/src/main/java/com/nageoffer/shortlink/agent/riskpolicy/outbox/RiskPolicySyncService.java`
- Create: `agent-service/src/main/java/com/nageoffer/shortlink/agent/riskpolicy/outbox/RiskPolicySyncWorker.java`
- Create: `agent-service/src/main/java/com/nageoffer/shortlink/agent/riskpolicy/outbox/RiskPolicySyncScheduler.java`
- Create: `agent-service/src/main/java/com/nageoffer/shortlink/agent/riskpolicy/outbox/RiskPolicyExpiryService.java`
- Create: `agent-service/src/main/java/com/nageoffer/shortlink/agent/riskpolicy/outbox/RiskPolicyExpiryScheduler.java`
- Create: `agent-service/src/main/java/com/nageoffer/shortlink/agent/riskpolicy/outbox/RiskPolicySyncInternalController.java`
- Create: `agent-service/src/main/java/com/nageoffer/shortlink/agent/riskpolicy/outbox/dto/RiskPolicySyncReplayReqDTO.java`
- Create: `agent-service/src/main/java/com/nageoffer/shortlink/agent/riskpolicy/outbox/dto/RiskPolicySyncReplayRespDTO.java`
- Modify: `agent-service/src/main/java/com/nageoffer/shortlink/agent/riskpolicy/repository/JdbcEffectiveRiskPolicyRepository.java`
- Modify: `agent-service/src/main/java/com/nageoffer/shortlink/agent/riskpolicy/repository/JdbcRiskPolicyRepository.java`
- Modify: `agent-service/src/main/java/com/nageoffer/shortlink/agent/riskpolicy/repository/JdbcRiskActionAuditRepository.java`
- Create: `agent-service/src/main/java/com/nageoffer/shortlink/agent/riskpolicy/action/RiskPolicyActionViewEnricher.java`
- Create: `agent-service/src/test/java/com/nageoffer/shortlink/agent/riskpolicy/RiskPolicySyncServiceTest.java`
- Create: `agent-service/src/test/java/com/nageoffer/shortlink/agent/riskpolicy/RiskPolicySyncWorkerTest.java`
- Create: `agent-service/src/test/java/com/nageoffer/shortlink/agent/riskpolicy/RiskPolicySyncSchedulerTest.java`
- Create: `agent-service/src/test/java/com/nageoffer/shortlink/agent/riskpolicy/RiskPolicyExpiryServiceTest.java`
- Create: `agent-service/src/test/java/com/nageoffer/shortlink/agent/riskpolicy/RiskPolicyExpirySchedulerTest.java`
- Create: `agent-service/src/test/java/com/nageoffer/shortlink/agent/riskpolicy/RiskPolicyActionViewEnricherTest.java`
- Create: `agent-service/src/test/java/com/nageoffer/shortlink/agent/riskpolicy/RiskPolicySyncInternalControllerTest.java`
- Modify: `agent-service/src/test/java/com/nageoffer/shortlink/agent/riskpolicy/RiskPolicyServiceTest.java`
- Modify: `agent-service/src/test/java/com/nageoffer/shortlink/agent/harness/security/InternalAgentApiFilterTest.java`
- Modify: `agent-service/src/test/java/com/nageoffer/shortlink/agent/infrastructure/config/SpringBeanConstructorContractTest.java`

- [ ] **Step 1: 写 stale UPSERT/DELETE 测试**

```java
@Test
void staleUpsertIsSkippedBeforeRedisCall() {
    Fixture fixture = fixture();
    fixture.effectiveVersion("key-1", "policy-2", 2L, RiskPolicyDesiredState.ACTIVE);
    RiskPolicySyncOutbox stale = upsertOutbox("outbox-1", "key-1", "policy-1", 1L);

    fixture.service.process(stale, "worker-1", NOW);

    verifyNoInteractions(fixture.publisher);
    verify(fixture.outboxRepository).markSkipped("outbox-1", "worker-1", "stale policy version", NOW);
}

@Test
void deleteUsesExpectedValueAndDoesNotBlindlyRemoveNewVersion() {
    Fixture fixture = fixture();
    fixture.effectiveVersion("key-1", "policy-1", 3L, RiskPolicyDesiredState.DISABLED);
    when(fixture.publisher.compareAndDelete("key-1", "version-2-value"))
            .thenReturn(RiskPolicyDeleteResult.VALUE_MISMATCH);

    fixture.service.process(deleteOutbox("outbox-3", "key-1", "policy-1", 3L, "version-2-value"), "worker-1", NOW);

    verify(fixture.publisher).compareAndDelete("key-1", "version-2-value");
    verify(fixture.outboxRepository).markSkipped(
            "outbox-3", "worker-1", "redis value mismatch", NOW
    );
}

@Test
void expiredUpsertIsConvertedToDeleteWithoutPublishing() {
    Fixture fixture = fixture();
    fixture.effectiveVersion("key-1", "policy-1", 4L, RiskPolicyDesiredState.ACTIVE);
    RiskPolicySyncOutbox expired = upsertOutbox(
            "outbox-4", "key-1", "policy-1", 4L, NOW.minusSeconds(1)
    );

    fixture.service.process(expired, "worker-1", NOW);

    verify(fixture.publisher, never()).publish(any(), any(), any());
    assertThat(fixture.effectiveRepository.findByPolicyKey("key-1"))
            .isPresent().get().extracting(EffectiveRiskPolicy::desiredState)
            .isEqualTo(RiskPolicyDesiredState.EXPIRED);
    assertThat(fixture.outboxRepository.findByPolicyKeyVersionAndOperation(
            "key-1", 4L, RiskPolicySyncOperation.DELETE
    ))
            .isPresent().get().extracting(RiskPolicySyncOutbox::operation)
            .isEqualTo(RiskPolicySyncOperation.DELETE);
}
```

补充：`DELETED` 和 `ALREADY_ABSENT` 标记 SUCCEEDED，并把 matching effective slot 标记 SYNCED；`VALUE_MISMATCH` 标记 SKIPPED，并把 matching effective slot 标记 DEAD，人工核对或修复 Redis 后可通过受审计 replay 重新校准。三种结果均只在 effective slot 的 policyId/version/desiredState 匹配时调用 Lua。

增加正常 TTL 到期测试：先让 UPSERT 成功，再把 Clock 推进到 `expireTime` 之后，调用 `RiskPolicyExpiryService.expireNext()`；断言 history -> EXPIRED、effective -> EXPIRED/PENDING、创建同 policyVersion 的 DELETE outbox，且 `expectedRedisValue` 精确等于 effective slot 保存的 `redisValueJson`。随后 Worker 收到 Redis `ALREADY_ABSENT` 时 effective -> SYNCED。

增加 Action View 实时投影测试：action 的持久 `resultJson.syncStatus=PENDING`，完全匹配的 effective slot 更新为 SYNCED 后，`RiskPolicyActionViewEnricher.enrich(...)` 返回 `syncStatus=SYNCED`、`desiredState=ACTIVE`、`effective=true`；若 slot 已指向更高版本，则返回 `effective=false` 和 history `policyStatus`，不把新版本同步状态冒充为旧动作状态。

- [ ] **Step 2: 写 retry/dead/replay 测试**

覆盖：Redis 异常 -> RETRY_WAIT；达到 maxAttempts -> DEAD；replay DEAD -> PENDING；`SKIPPED + REDIS_VALUE_MISMATCH` 允许人工 replay；其他 SKIPPED 不允许；错误脱敏和限长。

成功 replay 必须原子重置：

```text
status=PENDING
attemptCount=0
nextRetryTime=now
ownerToken=''
leaseUntil=NULL
lastError=''
matching effective.syncStatus=PENDING
```

测试在原任务已达到 maxAttempts 后执行 replay，再断言 `claimNext(...)` 能重新领取。只重置 status 而保留 attemptCount 的实现必须失败。

增加 MockMvc/API 和审计测试：

```java
@Test
void replayableOutboxUsesTrustedActorAndWritesAudit() throws Exception {
    mockMvc.perform(post("/internal/short-link-agent/v1/policy-sync/outbox/outbox-1/replay")
                    .header("X-Agent-Internal-Token", "internal-token")
                    .header("X-Agent-Username", "operator-1")
                    .header("X-Agent-UserId", "1001")
                    .header("X-Agent-RealName", "Risk Operator")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("{\"reason\":\"retry after redis recovery\"}"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.outboxId").value("outbox-1"))
            .andExpect(jsonPath("$.data.status").value("PENDING"));

    verify(syncService).replay(
            "outbox-1",
            new AgentActionActor("operator-1", "1001", "Risk Operator", ""),
            "retry after redis recovery"
    );
}
```

Repository 集成测试断言成功 replay 同一事务写一条 `OUTBOX_REPLAY` audit；非 DEAD/非 mismatch SKIPPED、并发 CAS 失败或缺少任一可信身份 Header 不写 audit。重复 replay 返回稳定 `POLICY_SYNC_OUTBOX_NOT_REPLAYABLE`，不制造重复审计。

- [ ] **Step 3: 运行测试并确认 RED**

```powershell
mvn -q -pl agent-service -Dtest=RiskPolicySyncServiceTest,RiskPolicySyncWorkerTest,RiskPolicySyncSchedulerTest,RiskPolicyExpiryServiceTest,RiskPolicyExpirySchedulerTest,RiskPolicyActionViewEnricherTest,RiskPolicySyncInternalControllerTest test
```

Expected: FAIL。

- [ ] **Step 4: 增加配置**

`AgentProperties.Risk.PolicySync`：

```text
enabled=true
leaseMinutes=5
maxAttempts=10
retryInitialSeconds=30
retryMaxSeconds=600
workerIntervalMillis=5000
expiryIntervalMillis=60000
expiryBatchSize=100
```

- [ ] **Step 5: 实现 Service 和 Worker**

`RiskPolicySyncService.process()`：

```text
读取 effective slot。
校验 policyId/version/desiredState。
UPSERT 调 publish。
DELETE 调 compareAndDelete。
UPSERT 的 expireTime <= now 时，在事务中把 matching effective slot 改为 EXPIRED，当前 UPSERT 标记 SKIPPED，并为同 policyVersion 创建 DELETE outbox；不得调用 publish。
DELETE 的 DELETED/ALREADY_ABSENT -> SUCCEEDED + effective SYNCED，VALUE_MISMATCH -> SKIPPED + effective DEAD。
成功更新 outbox 和 matching effective syncStatus。
stale 更新 SKIPPED。
异常由 Worker 计算退避并 recordFailure。
```

Worker 每次 `runNext()` 最多处理一个任务，Scheduler 使用 fixedDelay 配置重复调用。

`JdbcEffectiveRiskPolicyRepository` 增加：

```java
List<EffectiveRiskPolicy> findDueActive(LocalDateTime now, int limit);
Optional<EffectiveRiskPolicy> findByPolicyKeyForUpdate(String policyKey);
boolean markExpiredIfVersion(String policyKey, String policyId, long policyVersion, String outboxId);
```

`RiskPolicyExpiryService.expireNext(now)` 先取最多一条候选，再在 `TransactionTemplate` 中重新锁定校验，调用 history `markExpired`、effective `markExpiredIfVersion`，并以 slot 的 `redisValueJson` 创建 DELETE outbox。`RiskPolicyExpiryScheduler` 按 `expiryIntervalMillis` 循环最多 `expiryBatchSize` 条，单条失败隔离并脱敏记录。

`RiskPolicyActionViewEnricher` 实现 02A 的 `AgentActionViewEnricher` SPI，只处理 result 中同时存在 policyKey/policyId/policyVersion 的动作。它查询 effective/history 后覆盖实时 `syncStatus`、`desiredState`、`policyStatus`、`effective`，最后仍由 Harness 做 BLOCK_IP policyKey 脱敏。

- [ ] **Step 6: 实现 replay internal API**

```text
POST /internal/short-link-agent/v1/policy-sync/outbox/{outboxId}/replay
```

要求 internal token 以及可信 `X-Agent-Username`、`X-Agent-UserId`、`X-Agent-RealName`；Body 只含限长 `reason`；允许 `DEAD` 或 `SKIPPED + lastError=REDIS_VALUE_MISMATCH`，返回 `RiskPolicySyncReplayRespDTO(outboxId, status)`。`RiskPolicySyncService.replay(outboxId, AgentActionActor actor, reason)` 用 `TransactionTemplate` 锁定 outbox，校验 replayable 状态，重置领取字段和 matching effective.syncStatus，再写 `JdbcRiskActionAuditRepository.saveOutboxReplayAudit(...)`。Controller 测试覆盖 200、400、403、404、409，并确认响应不含 ownerToken、redisValueJson 或内部错误。

`RiskPolicySyncScheduler`、`RiskPolicySyncWorker` 或 Service 若为测试保留多个构造器，必须只有生产构造器标注 `@Autowired`，并在本 Task 立即运行 `SpringBeanConstructorContractTest`。

- [ ] **Step 7: 运行测试并提交**

```powershell
mvn -q -pl agent-service -Dtest=RiskPolicySyncServiceTest,RiskPolicySyncWorkerTest,RiskPolicySyncSchedulerTest,RiskPolicyExpiryServiceTest,RiskPolicyExpirySchedulerTest,RiskPolicyActionViewEnricherTest,RiskPolicySyncInternalControllerTest,RiskPolicyServiceTest,InternalAgentApiFilterTest,AgentRiskPropertiesTest,SpringBeanConstructorContractTest test
git add agent-service/src/main/java/com/nageoffer/shortlink/agent/riskpolicy agent-service/src/main/java/com/nageoffer/shortlink/agent/infrastructure/config/AgentProperties.java agent-service/src/test/java/com/nageoffer/shortlink/agent/riskpolicy agent-service/src/test/java/com/nageoffer/shortlink/agent/harness/security/InternalAgentApiFilterTest.java agent-service/src/test/java/com/nageoffer/shortlink/agent/infrastructure/config/AgentRiskPropertiesTest.java agent-service/src/test/java/com/nageoffer/shortlink/agent/infrastructure/config/SpringBeanConstructorContractTest.java
git commit -m "feat: synchronize risk policies through outbox"
```

## Task 7: 更新 Gateway 短链接范围 BLOCK_IP

**Files:**

- Modify: `agent-service/src/main/java/com/nageoffer/shortlink/agent/riskcommon/redis/RiskPolicyRedisKeyBuilder.java`
- Modify: `gateway/src/main/java/com/nageoffer/shortlink/gateway/risk/RiskPolicyRedisKeyBuilder.java`
- Modify: `gateway/src/main/java/com/nageoffer/shortlink/gateway/risk/RiskPolicyPayload.java`
- Modify: `gateway/src/main/java/com/nageoffer/shortlink/gateway/filter/RiskPolicyGatewayFilterFactory.java`
- Modify: `gateway/src/test/java/com/nageoffer/shortlink/gateway/risk/RiskPolicyRedisKeyBuilderTest.java`
- Modify: `gateway/src/test/java/com/nageoffer/shortlink/gateway/filter/RiskPolicyGatewayFilterFactoryTest.java`

- [ ] **Step 1: 写 key 和作用域失败测试**

```java
@Test
void buildsShortLinkScopedBlockIpKey() {
    RiskPolicyRedisKeyBuilder builder = new RiskPolicyRedisKeyBuilder();

    assertThat(builder.blockShortLinkIpKey("nurl.ink", "abc123", "hash-1"))
            .isEqualTo("risk:policy:short-link:block-ip:nurl.ink:abc123:hash-1");
}
```

Gateway filter 测试：同一 ipHash 访问 `abc123` 被 403，访问 `other` 正常放行；legacy global key 仍然阻断。

- [ ] **Step 2: 写 payload 元数据兼容测试**

带 `policyId`、`policyVersion` 的 LIMIT_RATE/TIME_WINDOW JSON 必须按旧逻辑解析。

- [ ] **Step 3: 运行测试并确认 RED**

```powershell
mvn -q -pl gateway -Dtest=RiskPolicyRedisKeyBuilderTest,RiskPolicyGatewayFilterFactoryTest test
```

Expected: FAIL。

- [ ] **Step 4: 修改 KeyBuilder 和 filter**

请求检查顺序：

```text
disable short link
short-link scoped block IP
legacy global block IP
time window
rate limit
```

新增 `policyId`、`policyVersion` 字段及 getter/setter，不改变现有策略字段。

- [ ] **Step 5: 运行 gateway 测试并提交**

```powershell
mvn -q -pl gateway test
git add agent-service/src/main/java/com/nageoffer/shortlink/agent/riskcommon/redis/RiskPolicyRedisKeyBuilder.java gateway/src/main gateway/src/test
git commit -m "feat: scope block ip policies to short links"
```

## Task 8: 注册正式风控 Executor 并更新现有闭环测试

**Files:**

- Create: `agent-service/src/main/java/com/nageoffer/shortlink/agent/riskpolicy/action/RiskPolicyActionConfiguration.java`
- Modify: `agent-service/src/main/java/com/nageoffer/shortlink/agent/riskpolicy/action/RiskActionProposalFactory.java`
- Modify: `agent-service/src/main/java/com/nageoffer/shortlink/agent/securityriskagent/node/RiskAutoActionNode.java`
- Modify: `agent-service/src/test/java/com/nageoffer/shortlink/agent/harness/action/AgentActionExecutorRegistryTest.java`
- Modify: `agent-service/src/test/java/com/nageoffer/shortlink/agent/riskpolicy/RiskPolicyActionExecutorTest.java`
- Modify: `agent-service/src/test/java/com/nageoffer/shortlink/agent/riskpolicy/RiskActionProposalFactoryTest.java`
- Modify: `agent-service/src/test/java/com/nageoffer/shortlink/agent/e2e/RiskProfilePolicyE2eTest.java`
- Modify: `agent-service/src/test/java/com/nageoffer/shortlink/agent/infrastructure/config/SpringBeanConstructorContractTest.java`

- [ ] **Step 1: 写 Spring 注册测试**

```java
@Test
void registersThreeRiskExecutorsAfterPolicyConsistencyIsAvailable() {
    AgentActionExecutorRegistry registry = registryFromSpringContext();

    assertThat(registry.findByType(RiskPolicyActionTypes.DISABLE_SHORT_LINK)).isPresent();
    assertThat(registry.findByType(RiskPolicyActionTypes.LIMIT_TIME_WINDOW)).isPresent();
    assertThat(registry.findByType(RiskPolicyActionTypes.BLOCK_IP)).isPresent();
}
```

同一步增加有效策略抑制测试：目标 `policyKey` 已有 `ACTIVE` 且 `expireTime` 为空或晚于当前 Clock 的 effective slot 时，即使旧 pending action 已结束也不再生成相同动作；slot 为 DISABLED/EXPIRED，或 ACTIVE 但已经到期时，允许基于新事件再次提案。该检查在 02C 才启用，因此 02B 不反向依赖 effective repository。

- [ ] **Step 2: 运行测试并确认 RED**

```powershell
mvn -q -pl agent-service -Dtest=AgentActionExecutorRegistryTest,RiskPolicyActionExecutorTest,RiskActionProposalFactoryTest test
```

Expected: FAIL，尚无配置 Bean。

- [ ] **Step 3: 实现配置类**

`RiskPolicyActionConfiguration` 暴露三个 `RiskPolicyActionExecutor` Bean，共享 `RiskPolicyActionPort` 和 `ObjectMapper`，每个 Bean 支持一个命名空间类型。

`RiskActionProposalFactory` 注入 `JdbcEffectiveRiskPolicyRepository` 和 `Clock`，在 rejection suppression 之后、构造 proposal 之前检查目标 policy key 的 `desiredState=ACTIVE && (expireTime == null || expireTime > now)`。若新增 Spring Bean 或现有 Bean 因测试构造器形成多构造器，生产构造器必须恰好一个 `@Autowired`；本 Task 必须保持 `SpringBeanConstructorContractTest` 通过。

- [ ] **Step 4: 更新自动 LIMIT_RATE 结果**

`RiskAutoActionNode` 返回：

```text
policyId
policyKey
policyVersion
policyStatus
syncStatus=PENDING
action=LIMIT_RATE
```

测试不得再验证 Redis 立即写入，而要验证 UPSERT outbox 已创建。

- [ ] **Step 5: 更新 RiskProfilePolicyE2eTest**

测试流程改为：

```text
执行 Graph。
断言自动策略 history/effective/outbox 已写。
手动调用 RiskPolicySyncWorker.runNext()。
断言 Mock Redis 收到 versioned payload。
断言 Gateway 合同数据可解析。
```

- [ ] **Step 6: 运行测试并提交**

```powershell
mvn -q -pl agent-service -Dtest=AgentActionExecutorRegistryTest,RiskPolicyActionExecutorTest,RiskActionProposalFactoryTest,RiskProfilePolicyE2eTest,SpringBeanConstructorContractTest test
git add agent-service/src/main/java/com/nageoffer/shortlink/agent/riskpolicy/action agent-service/src/main/java/com/nageoffer/shortlink/agent/securityriskagent/node/RiskAutoActionNode.java agent-service/src/test/java/com/nageoffer/shortlink/agent
git commit -m "feat: enable confirmed risk policy actions"
```

## Task 9: 02C 阶段验证和推送

**Files:**

- Modify: `plan/Agent平台增强/02_Agent生产就绪增强实施计划/02_待确认动作与策略一致性/00_总控索引.md`

- [ ] **Step 1: 运行相关模块测试**

```powershell
mvn -q -pl agent-service,gateway test
```

Expected: PASS。

- [ ] **Step 2: 验证无同步 Redis 写路径**

```powershell
rg -n "redisPublisher\.publish|redisPublisher\.revoke|stringRedisTemplate\.delete" agent-service/src/main/java/com/nageoffer/shortlink/agent/riskpolicy
```

Expected: 只允许 Outbox sync service/publisher 内存在 Redis 调用，`RiskPolicyService` 不得命中。

- [ ] **Step 3: 更新状态并推送**

将 02C 标记为“已完成，executor 已启用”。

```powershell
git add plan/Agent平台增强/02_Agent生产就绪增强实施计划
git commit -m "docs: complete risk policy consistency phase"
git push origin codex/pending-action-policy-consistency
```
