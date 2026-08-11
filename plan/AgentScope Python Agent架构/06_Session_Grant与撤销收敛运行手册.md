# AgentScope Python Agent Session Grant 与撤销收敛运行手册

> 状态：0.7.0 Consolidated Baseline，staging 联调与发布通道待完成  
> 日期：2026-07-18  
> 范围：`campaign-analysis` 的 Session Bootstrap、Token Refresh、Session Revoke、
> Token Revocation Check 与 `agent.session.revoked.v1`

## 1. 目标与边界

本切片把 Session 身份从浏览器自选字符串提升为 Java authority 持久化授权事实，并让短期
Delegation Token、同步校验和异步撤销事件共同形成收敛闭环。

以下边界不变：

1. Java admin 是 Session Grant、Token 撤销与 Outbox 的唯一写者。
2. `sid` 只能由 Java 服务端生成，浏览器不能提交 owner、tenant、scope 或 JTI。
3. Python 只运行 `campaign-analysis`，不得路由 `security-risk`。
4. Python 不获得 Java 业务 MySQL 或风险 Redis 写权限。
5. Token Exchange 与 Java capability 仍执行最终授权，事件不能替代同步校验。
6. 默认 legacy 路由保持不变，0.7.0 不代表 G1/G2 已通过。

## 2. 契约命名

外部 HTTP API 和事件使用 camelCase；JWT 延续 OAuth 风格紧凑 Claim；数据库使用 snake_case。
禁止同一边界同时接受两套别名。

| 语义 | HTTP / Event | JWT | 数据库 |
|---|---|---|---|
| Session | `sessionId` | `sid` | `session_id` |
| Tenant | 事件 `tenantId` | `tid` | `tenant_id` |
| Owner | 不返回 | `sub`、`preferred_username` | `owner_user_id`、`owner_username` |
| Agent 类型 | `agentType` | 不复制 | `agent_type` |
| Scope | 不返回 | `scp` | `scopes_json` |
| Grant 版本 | `grantVersion` | `grant_ver` | `grant_version` |
| Token ID | 校验接口 `tokenId` | `jti` | `latest_jti` / `token_jti` |
| 到期时间 | `*ExpiresAt` | `exp` | `expires_at` |
| 撤销原因 | `reasonCode` | 不复制 | `reason` |
| 事件标识/时间 | `eventId` / `occurredAt` | 不适用 | `event_id` / `occurred_at` |

共享 Draft 2020-12 Schema 与伪造 Golden Example 位于
`schemas/agent-identity/v1/`。示例 Token 不是可用凭据。

### 2.1 配置与启用不变量

Java admin：

| 环境变量 | 默认值 | 约束 |
|---|---:|---|
| `AGENT_SESSION_GRANT_ENABLED` | `false` | 执行并核验 authority 迁移后才能设为 `true` |
| `AGENT_SESSION_GRANT_TTL` | `PT8H` | 必须大于 0 且不超过 `PT24H` |
| `AGENT_DELEGATION_TOKEN_TTL` | `PT5M` | 不超过 5 分钟，实际 exp 还受 Session 到期时间约束 |
| `AGENT_TOKEN_EXCHANGE_ENABLED` | `false` | 生产启用时必须完成 TLS、mTLS 与证书指纹配置 |
| `AGENT_RUNTIME_AUTH_MODE` | `LEGACY` | 按 `LEGACY -> DUAL -> DELEGATION_JWT` 推进 |
| `AGENT_CAPABILITY_AUTH_MODE` | `LEGACY` | 按 `LEGACY -> DUAL -> AUTHORITY_TOKEN` 推进 |

Python Runtime：

| 环境变量 | 默认值 | 约束 |
|---|---:|---|
| `SHORTLINK_AGENT_DELEGATION_REVOCATION_MODE` | `disabled` | staging 验证通过后切 `authority` |
| `SHORTLINK_AGENT_DELEGATION_REVOCATION_CHECK_PATH` | `/internal/short-link-admin/v1/agent-identity/revocations/check` | 只允许绝对服务内路径 |
| `SHORTLINK_AGENT_REVOCATION_CACHE_TTL_SECONDS` | `5` | 大于 0 且不超过 30 秒 |
| `SHORTLINK_AGENT_REVOCATION_EVENT_TTL_SECONDS` | `360` | 必须覆盖 Token TTL 与 clock skew |
| `SHORTLINK_AGENT_REVOCATION_CACHE_MAX_ENTRIES` | `10000` | 同时约束缓存和唯一 inflight check 数 |
| `SHORTLINK_AGENT_REVOCATION_TIMEOUT_SECONDS` | `2` | 超时即 fail closed |
| `SHORTLINK_AGENT_REVOCATION_MAX_RESPONSE_BYTES` | `16384` | 超限响应拒绝 |

`authority` 模式复用 `SHORTLINK_AGENT_AUTHORITY_BASE_URL` 与
`SHORTLINK_AGENT_AUTHORITY_MTLS_CERT_FILE/KEY_FILE/CA_FILE`。非 local/test 环境必须使用 HTTPS，
客户端证书和私钥文件必须存在。启用顺序必须是“迁表 -> Java Session Grant -> mTLS check ->
Python authority mode -> DUAL/JWT 灰度”，禁止跳步。

## 3. 控制面 API

### 3.1 Bootstrap

```http
POST /api/short-link/admin/v1/agent/sessions
Content-Type: application/json
```

请求只含 `agentType` 和非身份 `clientContext`。当前 `agentType` 必须严格等于
`campaign-analysis`。owner 和 tenant 从已认证 Java 上下文恢复，scope 由服务端策略产生。

成功返回 200，响应字段严格为：

```json
{
  "sessionId": "as-s-example-1",
  "agentType": "campaign-analysis",
  "runtimeUrl": "/api/short-link/agent-runtime/v1/sessions/as-s-example-1",
  "runtimeToken": "<ephemeral-delegation-token>",
  "runtimeTokenExpiresAt": "2026-07-17T02:05:00Z",
  "sessionExpiresAt": "2026-07-17T10:00:00Z",
  "grantVersion": 1
}
```

`runtimeToken` 只允许进入浏览器内存，不得进入 URL、localStorage、日志、Trace 或埋点。

### 3.2 Refresh

```http
POST /api/short-link/admin/v1/agent/sessions/{sessionId}/token/refresh
```

请求无 JSON 字段。成功返回 200，响应与 Bootstrap 完全同构。服务端必须重新验证 tenant、owner、
ACTIVE 状态、Session 到期时间和当前版本。成功刷新时：

1. 生成新的 `jti` 并递增 `grant_ver`。
2. 在同一事务中将前一 `latest_jti` 写入撤销事实并切换最新 JTI。
3. 新旧 Token 不能同时被视为 ACTIVE。
4. 事务未提交时不能向调用方返回新 Token。

### 3.3 Revoke

```http
DELETE /api/short-link/admin/v1/agent/sessions/{sessionId}
```

请求无 JSON 字段。成功和同 owner 的重复撤销均返回 204 No Content，禁止返回 JSON body，因而不
存在 revoke response JSON Schema。首次撤销必须在一个数据库事务中完成：

1. 将 Grant 更新为 REVOKED 并递增版本。
2. 记录当前 `latest_jti` 撤销事实。
3. 插入 `agent.session.revoked.v1` Outbox 记录。

重复请求不得重复递增版本或发布语义重复事件。owner 不匹配必须表现为稳定的 404/403 策略，
不得泄漏 Session 是否属于其他用户。

### 3.4 Token Revocation Check

```http
POST /internal/short-link-admin/v1/agent-identity/revocations/check
Content-Type: application/json
```

该接口只允许 Runtime workload 经 mTLS 调用：

```json
{"sessionId":"as-s-example-1","grantVersion":2,"tokenId":"adt-example-2"}
```

ACTIVE 响应不含 `reasonCode`；拒绝响应必须含稳定原因码：

```json
{
  "active": false,
  "sessionId": "as-s-example-1",
  "grantVersion": 1,
  "tokenId": "adt-example-1",
  "checkedAt": "2026-07-17T02:05:30Z",
  "reasonCode": "TOKEN_REVOKED"
}
```

接口不得返回 owner、scope、Token 原文或数据库状态细节。网络失败、超时、非法响应和未知原因码
均按 fail closed 处理。

## 4. Token 与 Grant 一致性

Delegation Token 和 Authority Token 都必须包含正整数 `grant_ver`。Token Exchange 必须在签发
Authority Token 前校验：

1. `sid` 对应的 Grant 存在且为 ACTIVE。
2. `tid`、`sub`、`preferred_username` 与 Grant owner 一致。
3. Token `grant_ver` 等于 Grant 当前版本。
4. Token `jti` 等于当前 `latest_jti` 且不在撤销表中。
5. 请求 scope 是 Grant 和 Delegation Token scope 的交集子集。
6. Session 未到期，Authority Token 到期时间不超过父 Token 和 Session 到期时间。

任何不一致都必须拒绝，不得回退 legacy Header。

## 5. 撤销事件

`agent.session.revoked.v1` payload 固定为最小字段：

```json
{
  "eventId": "ase-example-1",
  "eventType": "agent.session.revoked.v1",
  "occurredAt": "2026-07-17T02:06:00Z",
  "tenantId": "tenant-default",
  "sessionId": "as-s-example-1",
  "grantVersion": 3,
  "status": "REVOKED",
  "reasonCode": "USER_CLOSED",
  "revokedAt": "2026-07-17T02:06:00Z"
}
```

禁止加入 owner、username、scope、agentType、JTI、Token、clientContext、Prompt、消息、工具结果或
业务 payload。Outbox 可保存 payload 的 canonical SHA-256，但事件消费者不得依赖数据库行号排序。

Python 以 `sessionId + grantVersion` 幂等消费：

- 小于本地已见版本：丢弃为陈旧事件。
- 等于已见版本：确认成功，不重复取消或回收。
- 大于已见版本：标记 Session 已撤销，阻止新 Run，取消活动 Run，并触发 Workspace 回收。

事件是低延迟收敛信号。即使事件积压，短 Token TTL、Token Exchange 和同步 revocation check 仍要
阻止继续授权。

## 6. 发布顺序

1. 先执行并验证 `t_agent_session_grant`、`t_agent_token_revocation`、
   `t_agent_authority_outbox` 的 expand-only 迁移。
2. 部署 Java 代码，但保持 legacy 默认模式和 Python 生产路由不变。
3. 验证 Bootstrap、Refresh、Revoke、Outbox 和 Revocation Check Provider Contract。
4. 部署 Python Consumer Contract 与撤销缓存，仍不切生产流量。
5. 在 staging 验证刷新竞态、重复撤销、事件重复/乱序/延迟、数据库回滚和网络 fail closed。
6. 依次执行既有 `S0 -> S1 -> S2` 灰度，不允许跳级。

## 7. 回滚

认证模式按 `AUTHORITY_TOKEN -> DUAL -> LEGACY` 顺序回滚。回滚只能停止新路径流量，不能删除
Grant、Token 撤销或 Outbox 事实，也不能降低已撤销 Session 的版本。数据库迁移保持 expand-only，
待完整保留窗口和消费者确认后才能执行独立 contract 发布。

若撤销事件通道故障：

1. 暂停继续放量并保留 Outbox 重试。
2. Python 对未命中撤销缓存的 Token 执行同步 check。
3. 必要时关闭强制 JWT 路由并按顺序退回 DUAL/LEGACY。
4. 不得通过清空撤销表、降低 Grant 版本或重新启用旧 JTI 恢复服务。

## 8. 验收门禁

- Bootstrap 的 `sid` 为服务端生成，Body 注入 tenant/owner/scope 必须失败。
- Bootstrap 与 Refresh response 字段集合完全一致。
- 并发 Refresh 只有一个新 Token 成为当前 Token，旧 JTI 立即失效。
- DELETE 成功为 204 且没有 body，重复调用不产生重复事件。
- Token Exchange 拒绝已撤销 JTI、旧 `grant_ver`、非当前 JTI 和过期 Session。
- Outbox 与 Grant 撤销同事务提交或同事务回滚。
- 撤销事件重复、乱序和延迟时 Python 结果一致。
- Event Schema 拒绝 Token、owner、scope、agentType、JTI 和业务原文。
- legacy 默认值、Java agent-service 和 `security-risk` 路由保持不变。
- 所有 Schema 通过 Draft 2020-12、format 与 Golden Example 校验。
