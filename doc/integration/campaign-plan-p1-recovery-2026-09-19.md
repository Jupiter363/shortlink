# P1 第一批：查询任务只恢复，不重新创建

跟踪：[Issue #62](https://github.com/Jupiter363/shortlink/issues/62)。本批完成跨服务恢复合同；P1 的持久化 Run／Action／ChildLedger、Artifact 与 PlanGraph 尚在后续实施范围，新运行入口保持关闭。

## 行为

- Agent 后端提供专用 `recoverExistingStatisticsJob`，没有新增模型工具。请求只发往 Admin 的 `/statistics/jobs/recover-existing`；旧 gateway 实现默认报告协议不可用，不调用 submit。
- Admin 使用当前可信主体重新授权，经专用 `/internal/analytics/v1/jobs/recover-existing` 转发。Analytics 在原提交／清理共用 gate 后锁定已有身份，检查原请求 hash、当前权限、冻结成员、epoch 和期限，只返回已有 jobId，无 INSERT、配额准入或新快照采集分支。
- 缺失／过期返回 `REPLAY_UNAVAILABLE`，请求不符为 `CONFLICT`；旧服务的 404／405／501、缺失必要回执字段为 `RECOVERY_PROTOCOL_UNAVAILABLE`。保留结构化错误码，任何恢复失败都不能回退创建。
- Agent、Admin 与 Analytics 的 requestId 长度统一为最多 96 字符。首次提交仍使用现有创建入口。

## 验证

仅使用后端定向测试、H2 和短生命周期 HTTP 替身；没有启动 Docker、应用服务或真实模型。最终 **86 项不同测试全部通过，无跳过**：

| 范围 | 用例 | 数量 |
| --- | --- | --- |
| Agent | StatisticsJobRecoveryTest、ShortLinkBusinessHttpGatewayTest、AgentAuthorityHttpIntegrationTest | 19 |
| Admin | AgentAnalyticsJobTest、AgentAnalyticsRecoveryHttpTest、AgentToolInternalMvcTest | 29 |
| Analytics | QueryJobServiceTest、QueryJobRecoveryControllerTest | 38 |

Agent 首次定向运行有两条旧风险发现夹具仍使用旧接口；仅修正这两条的 SYSTEM 租户发现→USER 授权流程并重跑，均通过。没有重复运行其余已通过用例。Analytics 的 H2 INSERT 触发器同时拒绝会被回滚的创建尝试，锁等待场景覆盖等待期间身份到期与被清理两种情况。

## 边界

- 本批没有宣称端到端持久化恢复已启用；新接口由后续耐久账本调用，未知提交仍须保留 `SUBMISSION_UNRESOLVED`。
- Admin 目前仍使用旧查询范围解析。成员变化时可能产生 hash／权限冲突，明确失败且不会创建；跨期冻结集合与范围证明在 P2 完成。
- 未运行真实 MySQL 锁竞争或已部署的跨服务测试。H2 验证恢复路径和清理等锁竞争不产生新记录，生产数据库验收需单列。

用户确认的持续推进规则已写入根 `AGENTS.md`：完成已批准计划的一项后继续下一项；只运行最小必要验证，不逐阶段等待确认。
