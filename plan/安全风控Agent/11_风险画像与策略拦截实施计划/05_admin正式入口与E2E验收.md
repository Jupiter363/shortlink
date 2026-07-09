# admin 正式入口与 E2E 验收 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (- [ ]) syntax for tracking.

**Goal:** 交付 admin 风险查询与审核正式入口、E2E 测试、本机闭环脚本和验收清单更新。

**Architecture:** admin 只做页面 facade 和 gid 归属校验，通过 Feign 调 agent-service internal risk API；E2E 最后串起 DeepSeek/MySQL/Redis/admin/project/gateway/agent-service。

**Tech Stack:** Java 17、Spring Boot 3.0.7、JUnit 5、MySQL/H2、Redis；按本批涉及模块额外使用 Spring Cloud Gateway、Spring AI Alibaba Graph、MockMvc 或 WebTestClient。

---

## 执行范围

覆盖 Task 13 - Task 14。
本批是最后联调批次，必须等 11-01 到 11-04 全部完成后执行。

## 执行前检查

- [ ] 确认 risk-center internal API 已可用。
- [ ] 确认 admin UserContext、GroupService 或等价能力可校验 gid 归属。
- [ ] 确认真实本机闭环只通过环境变量注入 `DEEPSEEK_API_KEY`、`RISK_HASH_SALT` 和 internal token。

## 任务明细

### Task 13: admin 风险查询与审核正式入口

**Files:**

- Create: `admin/src/main/java/com/nageoffer/shortlink/admin/remote/AgentRiskRemoteService.java`
- Create: `admin/src/main/java/com/nageoffer/shortlink/admin/remote/dto/req/RiskPolicyDisableReqDTO.java`
- Create: `admin/src/main/java/com/nageoffer/shortlink/admin/remote/dto/req/RiskReviewReqDTO.java`
- Create: `admin/src/main/java/com/nageoffer/shortlink/admin/remote/dto/resp/RiskPageRespDTO.java`
- Create: `admin/src/main/java/com/nageoffer/shortlink/admin/remote/dto/resp/RiskGroupOverviewRespDTO.java`
- Create: `admin/src/main/java/com/nageoffer/shortlink/admin/remote/dto/resp/RiskShortLinkCardRespDTO.java`
- Create: `admin/src/main/java/com/nageoffer/shortlink/admin/remote/dto/resp/RiskShortLinkDetailRespDTO.java`
- Create: `admin/src/main/java/com/nageoffer/shortlink/admin/remote/dto/resp/RiskEventRespDTO.java`
- Create: `admin/src/main/java/com/nageoffer/shortlink/admin/remote/dto/resp/RiskReviewRespDTO.java`
- Create: `admin/src/main/java/com/nageoffer/shortlink/admin/service/RiskCenterFacadeService.java`
- Create: `admin/src/main/java/com/nageoffer/shortlink/admin/service/impl/RiskCenterFacadeServiceImpl.java`
- Create: `admin/src/main/java/com/nageoffer/shortlink/admin/controller/RiskCenterController.java`
- Create: `admin/src/test/java/com/nageoffer/shortlink/admin/controller/RiskCenterControllerTest.java`
- Create: `admin/src/test/java/com/nageoffer/shortlink/admin/remote/AgentRiskRemoteServiceTest.java`
- Modify: `agent-service/src/main/java/com/nageoffer/shortlink/agent/riskcenter/api/RiskCenterInternalController.java`
- Modify: `agent-service/src/main/java/com/nageoffer/shortlink/agent/riskcenter/api/dto/RiskPolicyDisableReqDTO.java`
- Modify: `agent-service/src/main/java/com/nageoffer/shortlink/agent/riskcenter/service/RiskCenterService.java`
- Modify: `agent-service/src/main/java/com/nageoffer/shortlink/agent/riskprofile/repository/JdbcShortLinkRiskProfileRepository.java`
- Modify: `agent-service/src/main/java/com/nageoffer/shortlink/agent/riskpolicy/model/RiskPolicyDisableCommand.java`
- Modify: `agent-service/src/main/java/com/nageoffer/shortlink/agent/riskpolicy/service/RiskPolicyService.java`
- Modify: `agent-service/src/main/java/com/nageoffer/shortlink/agent/harness/security/InternalAgentApiFilter.java`
- Modify: `agent-service/src/main/java/com/nageoffer/shortlink/agent/infrastructure/config/AgentProperties.java`
- Modify: `agent-service/src/test/java/com/nageoffer/shortlink/agent/riskcenter/RiskCenterInternalControllerTest.java`
- Modify: `agent-service/src/test/java/com/nageoffer/shortlink/agent/riskpolicy/RiskPolicyServiceTest.java`
- Modify: `agent-service/src/test/java/com/nageoffer/shortlink/agent/riskprofile/RiskProfileRepositoryTest.java`
- Modify: `agent-service/src/test/java/com/nageoffer/shortlink/agent/harness/security/InternalAgentApiFilterTest.java`

补充安全收口：

```text
1. GET /risk/short-links 需要 gid 参数，admin 先校验 gid 归属，再调用 agent-service。
2. agent-service detail internal API 使用 `/risk/groups/{gid}/short-links/{domain}/{shortUri}`，repository 按 `gid + domain + shortUri` 查询，防止内部物化跨 gid 详情。
3. POST /risk/policies/{policyId}/disable 请求体携带 gid，agent-service 用 policyId 的真实 gid 二次校验，防止跨租户禁用策略。
4. agent-service internal token 默认 fail-closed；仅显式配置 `short-link.agent.security.internal-token-dev-mode=true` 时允许空 token 本地开发。
```

- [x] **Step 1: 写 admin controller 失败测试**

正式 API：

```text
GET /api/short-link/admin/v1/risk/groups/{gid}/overview
GET /api/short-link/admin/v1/risk/groups/{gid}/short-links
GET /api/short-link/admin/v1/risk/short-links?gid=g1&domain=nurl.ink&shortUri=abc123
GET /api/short-link/admin/v1/risk/events?gid=g1
POST /api/short-link/admin/v1/risk/reviews
POST /api/short-link/admin/v1/risk/policies/{policyId}/disable
```

断言：

```text
从 UserContext 取 username。
通过 AgentRiskRemoteService 调 agent-service internal API。
请求头携带 X-Agent-Internal-Token 与 X-Agent-Username。
gid 必须属于当前用户。
```

- [x] **Step 2: 写 Feign remote 测试**

参考现有 `AgentRemoteServiceFeignTest`，断言 mapping 和 header 名称正确。

- [x] **Step 3: 运行失败测试**

Run:

```bash
mvn -pl admin -Dtest=RiskCenterControllerTest,AgentRiskRemoteServiceTest test
```

Expected: FAIL，原因是 admin 风险入口不存在。

- [x] **Step 4: 实现 AgentRiskRemoteService**

Feign mapping：

```java
@FeignClient(value = "short-link-agent", url = "${short-link.agent.admin.remote-url:}")
public interface AgentRiskRemoteService {
    @GetMapping("/internal/short-link-agent/v1/risk/groups/{gid}/overview")
    Result<RiskGroupOverviewRespDTO> groupOverview(
            @RequestHeader(value = "X-Agent-Internal-Token", required = false) String internalToken,
            @RequestHeader("X-Agent-Username") String username,
            @RequestHeader(value = "X-Agent-UserId", required = false) String userId,
            @RequestHeader(value = "X-Agent-RealName", required = false) String realName,
            @PathVariable("gid") String gid);
}
```

每个方法必须包含：

```text
X-Agent-Internal-Token
X-Agent-Username
X-Agent-UserId
X-Agent-RealName
```

- [x] **Step 5: 实现 RiskCenterFacadeService**

职责：

```text
1. 校验 gid 归属。
2. 组装 internal headers。
3. 调 agent-service。
4. 不缓存风险详情。
5. 透传 Result 失败信息。
```

- [x] **Step 6: 实现 RiskCenterController**

Controller 不包含 LLM/Agent 调用，只调用 facade。

- [x] **Step 7: 运行通过测试**

Run:

```bash
mvn -pl admin -Dtest=RiskCenterControllerTest,AgentRiskRemoteServiceTest test
```

Expected: PASS。

- [x] **Step 8: 提交并推送**

提交前必须重新执行：

```bash
mvn -pl admin test
mvn -pl agent-service test
git diff --check
```

并执行 diff-only 敏感信息扫描，确认没有真实密钥和原始 IP/user 明细进入新增返回体。

```bash
git add admin/src/main/java/com/nageoffer/shortlink/admin/controller/RiskCenterController.java admin/src/main/java/com/nageoffer/shortlink/admin/remote/AgentRiskRemoteService.java admin/src/main/java/com/nageoffer/shortlink/admin/remote/dto/req/RiskPolicyDisableReqDTO.java admin/src/main/java/com/nageoffer/shortlink/admin/remote/dto/req/RiskReviewReqDTO.java admin/src/main/java/com/nageoffer/shortlink/admin/remote/dto/resp/RiskEventRespDTO.java admin/src/main/java/com/nageoffer/shortlink/admin/remote/dto/resp/RiskGroupOverviewRespDTO.java admin/src/main/java/com/nageoffer/shortlink/admin/remote/dto/resp/RiskPageRespDTO.java admin/src/main/java/com/nageoffer/shortlink/admin/remote/dto/resp/RiskReviewRespDTO.java admin/src/main/java/com/nageoffer/shortlink/admin/remote/dto/resp/RiskShortLinkCardRespDTO.java admin/src/main/java/com/nageoffer/shortlink/admin/remote/dto/resp/RiskShortLinkDetailRespDTO.java admin/src/main/java/com/nageoffer/shortlink/admin/service/RiskCenterFacadeService.java admin/src/main/java/com/nageoffer/shortlink/admin/service/impl/RiskCenterFacadeServiceImpl.java admin/src/test/java/com/nageoffer/shortlink/admin/controller/RiskCenterControllerTest.java admin/src/test/java/com/nageoffer/shortlink/admin/remote/AgentRiskRemoteServiceTest.java agent-service/src/main/java/com/nageoffer/shortlink/agent/harness/security/InternalAgentApiFilter.java agent-service/src/main/java/com/nageoffer/shortlink/agent/infrastructure/config/AgentProperties.java agent-service/src/main/java/com/nageoffer/shortlink/agent/riskcenter/api/RiskCenterInternalController.java agent-service/src/main/java/com/nageoffer/shortlink/agent/riskcenter/api/dto/RiskPolicyDisableReqDTO.java agent-service/src/main/java/com/nageoffer/shortlink/agent/riskcenter/service/RiskCenterService.java agent-service/src/main/java/com/nageoffer/shortlink/agent/riskpolicy/model/RiskPolicyDisableCommand.java agent-service/src/main/java/com/nageoffer/shortlink/agent/riskpolicy/service/RiskPolicyService.java agent-service/src/main/java/com/nageoffer/shortlink/agent/riskprofile/repository/JdbcShortLinkRiskProfileRepository.java agent-service/src/test/java/com/nageoffer/shortlink/agent/harness/security/InternalAgentApiFilterTest.java agent-service/src/test/java/com/nageoffer/shortlink/agent/riskcenter/RiskCenterInternalControllerTest.java agent-service/src/test/java/com/nageoffer/shortlink/agent/riskpolicy/RiskPolicyServiceTest.java agent-service/src/test/java/com/nageoffer/shortlink/agent/riskprofile/RiskProfileRepositoryTest.java plan/安全风控Agent/11_风险画像与策略拦截实施计划.md plan/安全风控Agent/11_风险画像与策略拦截实施计划/00_总控索引.md plan/安全风控Agent/11_风险画像与策略拦截实施计划/05_admin正式入口与E2E验收.md
git commit -m "feat: expose admin risk center facade"
git push
```

### Task 14: E2E 本机闭环脚本与验收

**Files:**

- Create: `agent-service/src/test/java/com/nageoffer/shortlink/agent/e2e/RiskProfilePolicyE2eTest.java`
- Create: `scripts/risk-profile-policy-e2e.ps1`
- Modify: `plan/安全风控Agent/05_验收清单.md`

- [ ] **Step 1: 写 E2E 测试**

`RiskProfilePolicyE2eTest` 使用 H2 + mock Redis + stub LLM，验证：

```text
1. 加载近 7 天活跃短链。
2. 生成短链画像。
3. 聚合分组画像与 riskTrend7d。
4. Top 10 异常短链进入 SecurityRisk Agent。
5. 写 RiskEvent 与 RiskSnapshot。
6. HIGH + score>=80 + 两个强 reasonCode 自动创建 LIMIT_RATE。
7. Redis 写入 rate-limit key。
8. checkpoint 不含原始 IP/user。
```

- [ ] **Step 2: 写本机脚本**

`scripts/risk-profile-policy-e2e.ps1`：

```powershell
param(
  [string]$AgentUrl = "http://127.0.0.1:8010",
  [string]$GatewayUrl = "http://127.0.0.1:8000",
  [string]$Domain = "nurl.ink",
  [string]$ShortUri = "abc123"
)

$ErrorActionPreference = "Stop"

Write-Host "1. health check agent-service"
Invoke-RestMethod "$AgentUrl/internal/short-link-agent/v1/health" -Headers @{
  "X-Agent-Username" = "e2e"
}

Write-Host "2. trigger risk profile batch"
Invoke-RestMethod "$AgentUrl/internal/short-link-agent/v1/risk/profiles/run-once" -Method Post -Headers @{
  "X-Agent-Username" = "e2e"
}

Write-Host "3. query group overview"
Invoke-RestMethod "$AgentUrl/internal/short-link-agent/v1/risk/groups/default/overview" -Headers @{
  "X-Agent-Username" = "e2e"
}

Write-Host "4. call gateway short link"
try {
  Invoke-WebRequest "$GatewayUrl/$ShortUri" -Headers @{ "Host" = $Domain } -MaximumRedirection 0
} catch {
  Write-Host $_.Exception.Message
}
```

脚本只用于本机手动闭环，不提交任何真实密钥。

- [ ] **Step 3: 运行模块测试**

Run:

```bash
mvn -pl gateway test
mvn -pl agent-service test
mvn -pl admin test
```

Expected: all PASS。

- [ ] **Step 4: 运行敏感信息扫描**

Run:

```bash
rg -n "sk-[A-Za-z0-9]{16,}|DEEPSEEK_API_KEY\\s*[:=]\\s*sk-|AGENT_INTERNAL_TOKEN\\s*[:=]\\s*sk-|RISK_HASH_SALT\\s*[:=]\\s*[^}\\s]+|rawIp|ipAddress|access_records\\.rawData" gateway agent-service admin plan scripts
```

Expected: no secret output；如果出现测试断言中的敏感字段名，确认它们只在“禁止出现”测试中存在。

- [ ] **Step 5: 本机真实闭环**

前置服务：

```text
MySQL 可用，AGENT_DATASOURCE_URL 指向统一库或 shortlink_agent 库。
Redis 可用，gateway 和 agent-service 指向同一个 Redis。
admin/project/gateway/agent-service 已启动。
DEEPSEEK_API_KEY 只通过环境变量注入。
RISK_HASH_SALT 只通过环境变量注入。
```

Run:

```powershell
[Environment]::SetEnvironmentVariable("DEEPSEEK_API_KEY", "<local-env-only>", "Process")
[Environment]::SetEnvironmentVariable("RISK_HASH_SALT", "<local-env-only>", "Process")
.\scripts\risk-profile-policy-e2e.ps1
```

Expected:

```text
agent-service health 返回 success。
risk profile run-once 返回 scannedShortLinks > 0。
group overview 返回 groupRiskLevel、riskTrend7d、topRiskShortLinks。
Redis 中存在 risk:policy:short-link:rate-limit:{domain}:{shortUri} 或本轮无自动策略时存在 pendingAction。
Gateway 命中策略时返回 429/403/404，未命中时继续转发 project。
```

- [ ] **Step 6: 更新验收清单**

在 `plan/安全风控Agent/05_验收清单.md` 增加：

```text
风险画像与策略拦截验收：
1. gateway 可验证路由生效。
2. RiskPolicyGatewayFilter 覆盖四类策略。
3. 短链画像、分组画像、7 天趋势可查询。
4. SecurityRisk Agent 只处理 Top 10 异常候选。
5. 风险查询 API 不走 LLM。
6. 人工审核可写入 review 并触发策略撤销。
7. LIMIT_RATE 自动策略满足确定性条件。
8. 原始 IP/user 不进入持久化和 LLM 链路。
```

- [ ] **Step 7: 提交并推送**

```bash
git add agent-service/src/test/java/com/nageoffer/shortlink/agent/e2e/RiskProfilePolicyE2eTest.java scripts/risk-profile-policy-e2e.ps1 plan/安全风控Agent/05_验收清单.md
git commit -m "test: add risk profile policy e2e validation"
git push
```


## 批次完成验收

Run:

```bash
mvn -pl gateway test
mvn -pl agent-service test
mvn -pl admin test
rg -n "sk-[A-Za-z0-9]{16,}|DEEPSEEK_API_KEY\\s*[:=]\\s*sk-|AGENT_INTERNAL_TOKEN\\s*[:=]\\s*sk-|RISK_HASH_SALT\\s*[:=]\\s*[^}\\s]+|rawIp|ipAddress|access_records\\.rawData" gateway agent-service admin plan scripts
git diff --check
```

Expected:

```text
三模块测试 PASS。
E2E 脚本不提交真实密钥。
admin 正式入口校验 gid 归属并透传 internal headers。
Gateway 命中 Redis 策略时可返回 429/403/404。
```
