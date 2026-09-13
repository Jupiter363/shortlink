# 2026-09-13 双 Agent 管理前端 UAT

**后续更新（22:35）**：官方 `deepseek-flash`、真实异步统计、风险画像及后台分析 Job 的主业务阻断已修复，见 [业务链路最终补验](business-unblock-followup.md)。下文保留 20:43–20:49 无密钥阶段的原始结论，仅作为历史记录；当前缺失维度和策略审批边界以最新补验为准。模型接入阶段另见 [Flash 补验记录](deepseek-flash-followup.md)。

本轮使用新版 Command、Admin、Redirect、Agent Service 与 APISIX，前端使用恢复后的 NageOffer 管理控制台。投放分析与安全风控已经作为两个独立入口接入同一工作台，浏览器仍只访问 Admin 的公网 API，不直接访问 Agent 内部接口。

**前端接入与两条 Graph 基础链路条件通过。** 独立路由、会话与结果隔离、执行期间交互锁定、投放分析业务工具、两类 Graph Trace、checkpoint 和鉴权负例均通过。真实 LLM、完整异步统计工具、风险画像和策略动作闭环尚未满足验收条件，因此本报告不把 Agent 整体业务能力记为完全通过。

## 范围与拓扑

```text
Browser :5174
  -> Nginx /api
  -> APISIX :19080 (Host: admin.local.test)
  -> Admin :8002
  -> Agent Service :8010
  -> Command / Analytics API / MySQL
```

受测入口：

- 投放分析：`/home/agent/campaign-analysis`
- 安全风控：`/home/agent/security-risk`
- 兼容入口：`/home/agent` 重定向至投放分析
- 共用 API：`GET /api/short-link/admin/v1/agent/health`、`POST /api/short-link/admin/v1/agent/chat`

Chat 请求体只包含 `sessionId`、`agentType`、`message`。浏览器登录态由 Axios 统一附加，Admin 从已验证的 `UserContext` 生成可信 Agent 身份。

## 受测环境与源码身份

| 项目 | 实际值 |
| --- | --- |
| 验收时间 | 2026-09-13 20:43–20:49，Asia/Shanghai |
| 分支 / 基线提交 | `codex/fix-redirect-load-failures` / `14273abaa779178c885553cab4a064b90e705ab2` |
| 工作树 | 有未提交修改；本报告用源码 SHA256 绑定本次构建 |
| 前端 | `http://127.0.0.1:5174`，Nginx 读取 `frontend/console-vue/dist` |
| Java 服务 | Command、Admin、Redirect、Agent 均为 Docker `healthy` |
| 基础设施 | MySQL、Redis、Kafka 均为 Docker `healthy`；MinIO、APISIX、Frontend 为 `running` |
| LLM | `LLM_API_KEY` 未配置 |
| Analytics | Compose 配置了 Admin 目标地址，但没有启动 Analytics API、Worker、Flink 或 ClickHouse |

源码和测试观察值见 [uat-summary.json](uat-summary.json)、[browser-cases.json](browser-cases.json) 与 [checkpoint-evidence.json](checkpoint-evidence.json)。收据不包含登录口令、Token、内部鉴权值或完整会话 ID。

## 验收矩阵

| 项目 | 状态 | 实际结果 |
| --- | --- | --- |
| 旧前端登录与管理页 | PASS | 既有管理入口保持可用，UAT 账号已登录并进入工作台 |
| 双 Agent 顶部入口 | PASS | 顶部导航分别显示“投放分析 Agent”和“安全风控 Agent”，活动状态随路由变化 |
| 独立直达与刷新 | PASS | 两个路由均可直接访问；刷新后 Agent 类型保持正确，结果区回到 READY |
| 前进 / 后退 | PASS | 浏览历史可恢复对应 Agent，未显示另一 Agent 的旧回答 |
| Agent 类型白名单 | PASS | 页面状态和发送值只接受两种受支持类型，其他值回退到投放分析 |
| 会话隔离 | PASS | 两个 Agent 使用各自的本地会话键；浏览器观测到不同的脱敏会话前缀 |
| 结果隔离 | PASS | 投放分析返回结果后切换安全风控，旧回答、卡片和 Trace 均被清空 |
| 执行期间交互锁定 | PASS | 在受控 2.2 秒后端暂停窗口中，新会话、两类 Agent 选择和输入框均为 disabled；恢复后请求 HTTP 200 |
| 投放分析 Graph | PASS | 首次独立执行 HTTP 200；返回分组摘要、1 个分组、1 条短链及 6 个成功 Trace 节点 |
| 安全风控 Graph | PARTIAL | HTTP 200、9 个成功 Trace 节点、checkpoint FINISHED；没有风险画像、风险卡片或 Tool 证据 |
| 风控动作状态展示 | PARTIAL | 前端已区分 `pending_confirmation`、`executed`、`not_fully_applied` 并兼容 `policies`；当前运行未返回动作，无法做真实动作回执验收 |
| 服务健康展示 | PASS | 健康入口 HTTP 200；UI 明确显示“Agent 服务可达”，不再暗示两个 Graph 及其依赖全部健康 |
| 公网未登录拒绝 | PASS | 不带登录态访问 Admin Agent health 返回 401 / `INVALID_SESSION` |
| 内网令牌拒绝 | PASS | 不带内部令牌直访 Agent internal health 返回 401 / `Invalid internal token` |
| 浏览器控制台 | PASS | 最终构建复验日志为空 |
| Lint / Build | PASS | `npm run lint` 与 `npm run build` 均以退出码 0 完成 |
| 真实 LLM 回答 | BLOCKED | 两个 Agent 均按预期返回 API key 未配置的降级回答和 warning |
| 完整统计工具链 | BLOCKED | 本地 Compose 没有 Analytics API、Worker、Flink 和 ClickHouse |
| 风险画像 / 策略闭环 | FAIL | 4 个画像批次均失败，分组画像和短链画像均为 0 |

`PARTIAL` 表示当前入口和一部分链路已运行，但业务证据不足；`BLOCKED` 表示验收环境未提供依赖；`FAIL` 表示运行中已观察到确定失败。

## 浏览器与网关证据

投放分析首次在新路由执行时，APISIX 记录 Admin 上游 HTTP 200、总耗时约 0.117 秒。页面显示 `campaign-analysis-graph / v07-tenant-evidence` 的执行信息、分组摘要和 6 个成功节点。由于 LLM key 为空，最终文字回答为服务就绪但模型未配置。

安全风控首次在新路由执行时，APISIX 记录 Admin 上游 HTTP 200、总耗时约 0.182 秒。页面显示 9 个成功节点和模型未配置提示。Graph 跑通只能证明编排与持久化可运行；本轮结果没有 Tool、风险卡片或风险画像证据。

交互锁定使用一次受控故障窗口：临时暂停 Agent 容器 2.2 秒，在请求挂起期间同时检查四个控件均不可操作，随后自动恢复容器。对应请求在 APISIX 记录 HTTP 200、约 2.355 秒；测试后 Agent 恢复 `healthy`。这不是性能测试。

## Checkpoint 与风险画像证据

最终查询到：

| Graph | 版本 | 状态 | 记录数 | 最近更新时间（MySQL/UTC） |
| --- | --- | --- | --- | --- |
| `campaign-analysis-graph` | `v07-tenant-evidence` | FINISHED | 2 | 2026-09-13 12:47:18 |
| `security-risk-graph` | `v1` | FINISHED | 2 | 2026-09-13 12:46:21 |

Checkpoint 证明 Graph 已保存终态，不是统计或风险事实源。安全风控的画像前置链路仍有确定失败：`t_agent_risk_profile_batch` 中 4 个批次全部为 `FAILED`，错误均为 `Current profile access could not be authorized`；分组画像与短链画像表记录数均为 0。

代码复核定位到候选发现契约冲突：风险画像任务以空 `gid/fullShortUrl/linkIds` 调用授权解析，而 Command 明确拒绝空范围请求。定时任务当前还使用 `SYSTEM/localdev` 单租户身份，无法覆盖其他租户。修复方向应为受控、分页、可审计的逐租户候选发现协议。

## 构建检查

```powershell
cd frontend/console-vue
npm run lint
npm run build
```

两条命令退出码均为 0。Vite 报告部分资源超过 500 kB，主要来自既有 Vanta/Three、ECharts 与地图资源；它不阻断本轮功能验收，后续可按路由拆包优化首屏加载。

## 本轮修复

- 为两个 Agent 增加独立路由和顶部入口，保留旧 `/home/agent` 兼容重定向。
- 每个 Agent 使用独立会话；路由切换时清理指令、回答、卡片、告警和 Trace。
- 绑定请求的 Agent 和 session 快照，迟到响应不能覆盖另一 Agent 的页面状态。
- 请求期间锁定 Agent 选择、新会话、预设和输入框。
- 风控动作按后端状态展示，并同时兼容 `arguments` 与 `policies` 详情。
- 健康文案改为服务级“可达”，并补充 401、403、429、超时和网络错误提示。

## 阻断与后续验收门槛

1. 配置有效的 LLM key 后，分别复验真实模型回答、引用证据与异常降级。
2. 将 Analytics API、Worker、Flink、ClickHouse 纳入本地 UAT 编排，再验收分组统计、访问记录和异步查询 Job。
3. 修复风险画像候选发现与多租户调度，确认画像表产生数据后，再验收 `risk_signal`、风险审核动作、策略执行和审计回执。
4. 为 Agent Chat DTO 增加必填、长度和枚举校验；当前空值和未知 `agentType` 的后端边界没有达到正式验收要求。
5. 将静态 health 升级为 readiness，至少分别反映 MySQL、Admin、Analytics 和 LLM 状态。

本轮未做压力、容量或生产切流验证。Docker 服务在报告完成时继续运行，便于后续补齐依赖后复验。

[返回文档目录](../../README.md) · [前端说明](../../development/frontend-console.md) · [投放分析验收清单](../../plan/智能投放与分析Agent/短链接项目_智能投放与分析Agent_正式版验收清单_最终版.md) · [安全风控验收清单](../../plan/安全风控Agent/05_验收清单.md)
