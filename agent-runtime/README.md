# Shortlink Agent Runtime

基于 Python 3.11、FastAPI 与 AgentScope 2.0 的 Agent Runtime。当前版本 `0.7.0` 是可回滚的
只读 Campaign Agent 迁移切片，不替代 Java 的审批、风险策略、业务事实与 Gateway
Redis 写入职责。

## 当前实现

- AgentScope 固定为 `2.0.4.post1`，且只在 adapter 层出现。
- 提供与 Java `AgentRunResult` 兼容的同步 `/chat` 接口。
- 仅迁移 `campaign-analysis`；`security-risk` 仍保留在 Java 服务。
- 通过 admin 调用五个只读 Tool：
  `list_groups`、`page_short_links`、`get_short_link_stats`、
  `get_group_stats`、`get_group_access_records`。
- `list_groups`、`page_short_links` 与 `get_group_stats` 默认调用版本化语义能力
  `POST /internal/short-link-admin/v1/agent-capabilities/v1/groups/list`、
  `POST /internal/short-link-admin/v1/agent-capabilities/v1/short-links/query` 和
  `POST /internal/short-link-admin/v1/agent-capabilities/v1/group-stats/query`；其余两个 Tool
  仍使用迁移期 `/agent-tools/*` 接口。
- 对 v1 响应严格校验字段集合、`schemaVersion`、`requestId`、Snapshot 时间与来源以及
  canonical JSON SHA-256；分组列表额外拒绝 owner/identity 字段、重复 gid、负数短链数和
  超过 1000 行的响应，分组统计不接受负数指标或不一致的请求 gid。短链分页额外校验分页
  回显和算术、唯一 URL、有效期关系与非负指标，并拒绝数据库 ID、原始 URL、owner、domain、
  shortUri 和 favicon 等越界字段。
- 支持 ES256 Delegation JWT、异步 JWKS 缓存、`sid`/scope 绑定和 Claims 身份派生。
- Delegation/Authority Token 强制携带 `grant_ver`；可通过 mTLS 同步核验 Java 持久化的
  Session Grant、当前 JTI 与撤销状态。
- 撤销核验使用短 TTL 有界 LRU、唯一请求并发合并和总 inflight 上限；Authority 不可用或
  响应契约漂移时 fail closed。`agent.session.revoked.v1` 可作为主动拒绝加速信号。
- 支持使用 mTLS 客户端证书将 Runtime Token 交换为最长 120 秒的 Authority Token；
  Authority Token 只缓存在当前请求上下文，不进入 Actor、日志或全局缓存。
- `legacy`、`dual`、强制 JWT 三阶段可配置；Bearer 一旦出现但验证失败，不回退静态令牌。
- 不注册 Bash、Write、Edit 或任意网络访问 Tool。
- Tool 路径使用 allowlist，并限制超时、响应大小、日期范围和分页。
- 进入模型和兼容响应前执行字段最小化、凭据移除和 IP/身份脱敏。
- 内部接口默认 fail closed；模型未配置时 readiness 返回 `503`。

## Miniconda 环境

```powershell
D:\miniconda\Scripts\conda.exe env create -f environment.yml
D:\miniconda\Scripts\conda.exe run -n shortlink-agent python -m pip install uv==0.11.29
conda activate shortlink-agent
$env:VIRTUAL_ENV = $env:CONDA_PREFIX
uv sync --active --locked --extra dev
```

`environment.yml` 固定 Python 运行时，`uv.lock` 固定 Python 包解析结果。CI 和生产构建必须
使用 `--locked`，禁止部署时隐式更新依赖。

当前工作区已创建环境：

```text
D:\miniconda\envs\shortlink-agent
Python 3.11.15
AgentScope 2.0.4.post1
```

## 配置

| 环境变量 | 作用 | 默认值 |
|---|---|---|
| `SHORTLINK_AGENT_INTERNAL_TOKEN` | admin 调 Python 兼容接口的令牌 | 空，且默认拒绝 |
| `SHORTLINK_AGENT_INTERNAL_TOKEN_DEV_MODE` | 仅本地允许空令牌 | `false` |
| `SHORTLINK_AGENT_RUNTIME_AUTH_MODE` | 入站 chat 鉴权：`legacy`/`dual`/`delegation_jwt` | `legacy` |
| `SHORTLINK_AGENT_DELEGATION_JWKS_URL` | Java admin JWKS 地址 | admin 本地地址 |
| `SHORTLINK_AGENT_DELEGATION_ISSUER` | Runtime Token issuer | `shortlink-admin` |
| `SHORTLINK_AGENT_DELEGATION_AUDIENCE` | Runtime Token audience | `shortlink-agent-runtime` |
| `SHORTLINK_AGENT_JWKS_CACHE_TTL_SECONDS` | JWKS 缓存上限 | `300` |
| `SHORTLINK_AGENT_DELEGATION_REVOCATION_MODE` | 撤销核验：`disabled`/`authority` | `disabled` |
| `SHORTLINK_AGENT_DELEGATION_REVOCATION_CHECK_PATH` | Java 撤销核验路径 | `/internal/.../revocations/check` |
| `SHORTLINK_AGENT_REVOCATION_CACHE_TTL_SECONDS` | active/拒绝结果本地缓存秒数 | `5` |
| `SHORTLINK_AGENT_REVOCATION_CACHE_MAX_ENTRIES` | 缓存及并发唯一核验硬上限 | `10000` |
| `SHORTLINK_AGENT_AUTHORITY_BASE_URL` | Java admin capability 地址 | `http://127.0.0.1:8002` |
| `SHORTLINK_AGENT_AUTHORITY_INTERNAL_TOKEN` | Python 调 Java 的过渡令牌 | 回退到入站令牌 |
| `SHORTLINK_AGENT_GROUPS_LIST_CONTRACT` | 分组列表契约；独立回滚设为 `legacy` | `v1` |
| `SHORTLINK_AGENT_SHORT_LINKS_CONTRACT` | 短链分页契约；独立回滚设为 `legacy` | `v1` |
| `SHORTLINK_AGENT_GROUP_STATS_CONTRACT` | 分组统计契约；紧急回滚设为 `legacy` | `v1` |
| `SHORTLINK_AGENT_V1_CAPABILITY_AUTH_MODE` | v1 capability 鉴权：`legacy`/`token_exchange` | `legacy` |
| `SHORTLINK_AGENT_AUTHORITY_MTLS_CERT_FILE` | Runtime 客户端证书链 | 空 |
| `SHORTLINK_AGENT_AUTHORITY_MTLS_KEY_FILE` | Runtime 客户端私钥 | 空 |
| `SHORTLINK_AGENT_AUTHORITY_MTLS_CA_FILE` | Java Authority 信任 CA，可选 | 系统 CA |
| `SHORTLINK_AGENT_MODEL_API_KEY` | DeepSeek API Key | 空 |
| `SHORTLINK_AGENT_MODEL_BASE_URL` | 模型 API 地址 | `https://api.deepseek.com` |
| `SHORTLINK_AGENT_MODEL_NAME` | 模型名 | `deepseek-v4-flash` |
| `SHORTLINK_AGENT_RUN_TIMEOUT_SECONDS` | 单次 Run 总时限 | `60` |

兼容迁移期间也读取 `AGENT_INTERNAL_TOKEN`、`DEEPSEEK_API_KEY`、`LLM_API_KEY`、
`LLM_BASE_URL` 和 `LLM_MODEL`。生产环境禁止启用空令牌开发模式。

## 启动

```powershell
$env:SHORTLINK_AGENT_INTERNAL_TOKEN = '<local-internal-token>'
$env:SHORTLINK_AGENT_MODEL_API_KEY = '<model-api-key>'

D:\miniconda\Scripts\conda.exe run -n shortlink-agent `
  python -m uvicorn shortlink_agent.main:app `
  --app-dir src --host 127.0.0.1 --port 8011
```

接口：

```text
GET  /health/live
GET  /health/ready
GET  /internal/short-link-agent/v1/health
POST /internal/short-link-agent/v1/chat
```

## Java Capability 契约

`list_groups` 的 v1 请求 Body 严格为 `{}`，身份与 owner 不得进入 Body。Java 从已认证的
`UserContext` 查询当前用户分组，最多返回 1000 个互不重复的 gid，并仅发布 `gid`、`name`、
`sortOrder`、`shortLinkCount`。Authority Token 所需 scope 为 `capability:group:read`。

`page_short_links` 的 v1 请求显式携带 `gid/current/size/sort`，排序只接受
`CREATED_AT_DESC`、`TODAY_*_DESC` 与 `TOTAL_*_DESC`。Java 先重新校验 gid 所有权，再调用
既有业务 Provider；响应保留 `records/total/current/size/pages/hasNext` 分页语义。每条记录只
发布 `fullShortUrl/describe/validity/expiresAt/createdAt` 和 today/total 的 PV、UV、UIP 指标；
`CUSTOM` 的 `expiresAt` 必须晚于 `createdAt`。Authority Token 所需最小 scope 同样为
`capability:group:read`。

`get_group_stats` 的 v1 请求使用 RFC 3339 时间和半开区间 `[start,end)`。当前 Java Provider
按自然日聚合，因此 start/end 必须对齐 `timeRange.timezone` 的本地零点；Java 将其映射为
Provider 的闭区间 `startDate` 到 `endDate - 1 day`。单次范围限制为 1 到 366 天。

成功响应直接返回版本化 capability payload，并携带 Java 权威侧生成的 Snapshot；当前
Snapshot TTL 为 300 秒。共享 JSON Schema 和 Golden Fixtures 位于
`../schemas/agent-capabilities/v1/`，由 Java Provider Test 与 Python Consumer Test 共同验证。

三个 v1 capability 使用独立回滚开关，只切换对应读取路径，不改变业务数据：

```powershell
$env:SHORTLINK_AGENT_GROUPS_LIST_CONTRACT = 'legacy'
$env:SHORTLINK_AGENT_SHORT_LINKS_CONTRACT = 'legacy'
$env:SHORTLINK_AGENT_GROUP_STATS_CONTRACT = 'legacy'
```

`SHORTLINK_AGENT_V1_CAPABILITY_AUTH_MODE=token_exchange` 时，三个 v1 capability 不再发送
内部令牌或可信用户 Header，而是发送由 mTLS Token Exchange 获得的最小 scope Authority
Token。其余两个旧
`/agent-tools/*` 仍走 legacy，不能据此宣称所有 capability 已退出过渡认证。

共享身份、JWKS 和 Token Exchange Schema 位于 `../schemas/agent-identity/v1/`。完整配置矩阵、
证书要求、密钥轮换和回滚步骤见
`../plan/AgentScope Python Agent架构/05_身份安全切片运行手册.md`。
Session Bootstrap、刷新、撤销、同步核验和事件契约见
`../plan/AgentScope Python Agent架构/06_Session_Grant与撤销收敛运行手册.md`。

## 验证

```powershell
D:\miniconda\Scripts\conda.exe run -n shortlink-agent python -m pytest
D:\miniconda\Scripts\conda.exe run -n shortlink-agent python -m ruff check .
D:\miniconda\Scripts\conda.exe run -n shortlink-agent python -m ruff format --check .
D:\miniconda\Scripts\conda.exe run -n shortlink-agent python -m pip check
```

Java 兼容 health 响应保持不变：

```json
{
  "success": true,
  "code": "0",
  "message": "success",
  "data": {
    "status": "OK",
    "service": "short-link-agent"
  }
}
```

## 切流约束

当前版本用于影子流量和契约验证，不应直接承接全部生产流量：

1. 兼容 `/chat` 每次创建隔离 AgentScope State，尚未实现多实例 Runtime Session。
2. 只有 v1 `groups/list`、`short-links/query` 与 `group-stats/query` 具备 Authority Token 路径；
   其余旧 Tool
   仍使用过渡内部令牌。
3. Session、Run、Event、SSE 恢复、RedisMessageBus 与 Sandbox 仍按路线图实施。
4. 只有 `campaign-analysis` 具备 Python 实现；风险 Agent 不能切到本服务。
5. 完成按 Agent 类型的灰度路由、对比测试和回滚演练后才能扩大流量。
6. Java 已实现持久 Session Grant、JTI 撤销表和事务 Outbox；但 Outbox 发布器、Python
   多实例事件消费者、活动 Run 取消及 Browser 直连 Runtime 尚未完成，因此 `0.7.0` 不是
   生产多租户完成态。

最终边界与迁移门禁见：

- `../plan/AgentScope Python Agent架构/01_最终架构指导纲领.md`
- `../plan/AgentScope Python Agent架构/02_迁移实施路线图与验收门禁.md`
- `../plan/AgentScope Python Agent架构/03_接口事件与数据契约.md`
- `../plan/AgentScope Python Agent架构/05_身份安全切片运行手册.md`
- `../plan/AgentScope Python Agent架构/06_Session_Grant与撤销收敛运行手册.md`
