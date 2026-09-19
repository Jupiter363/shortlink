# P3 模型调用账本 · 2026-09-20

关联 Issue #62。本批实现模型调用身份及公开响应的耐久基础，尚未接入原生 ReactAgent 的模型拦截器，也不开放 Driver 的 REACT 入口。

## 实现

- MODEL 使用现有 Child 的派发资格、回调生命周期和进程死亡证明；独立 `action_kind=MODEL`，不伪造 TOOL/SKILL 或 HTTP 请求。
- 服务端按 Run、revision、Step 与轮次生成稳定 action/child/request 身份。模型配置、请求正文与 attempt 不改变同一轮次的身份；已存在的请求不能被改写。
- 准入要求当前运行中的 StepPermit、冻结的 REACT Step 和 policy/inputSet、注册合同批准、输入 Artifact 的当前权限、内容校验和有效期。
- 请求仅包含闭合的消息和工具定义，验证工具调用与响应配对。响应仅保存公开文本和标准工具调用，拒绝额外字段与 reasoning/provider 元数据。保存响应不等于批准执行返回的工具。
- 响应与 Child READY 同事务提交；它不是业务 Artifact，不参与统计结果接收或父级数据覆盖校验。存储写失败不能留下 READY 或半条响应。
- 结果未知保留 `MODEL_RESULT_UNKNOWN`，不自动重新调用模型。取消或 Future 完成不清除活跃回调；正常执行由实际 finally 退出，崩溃恢复仅接受既有进程死亡证明。

## 验证

四项直接相关用例通过：

- `CampaignModelInvocationTest` 三项：同轮次改身份／改正文拒绝、真实数据库约束导致响应写入回滚、重开读取相同响应、统计路径零调用、撤权／篡改／过期／取消拒绝，以及 ALIVE／UNKNOWN 阻断与 DEAD 接管分类。
- `JdbcCampaignRecoveryStoreTest#provedDeadCallbacksAreClassifiedExactlyWhileReadyWaitingAndUnresolvedEvidenceIsPreserved`：既有无 MODEL 列的账本恢复兼容。

首次运行停于测试编译，修复跨包辅助方法引用后，旧恢复用例通过；三个新用例暴露 Step 合同 hash 字段误用。实现已改为复用现有 StepStore 校验 `specification_hash`，随后仅重跑这三个新用例，全部通过。最后两份有效运行日志为 `.work/model-ledger-retest.log`（旧恢复一项通过）与 `.work/model-ledger-step-fix.log`（新三项通过）。没有重复已通过且未受后续修改影响的旧恢复用例。

所有验证仅使用后端 H2 与受控进程存活证明。没有调用真实模型、业务网关或启动 Docker／应用服务。

## 边界与后续

迁移 `V20260920_8__campaign_model_invocation.sql` 需先于 MODEL 写入应用；旧二进制不能读取 MODEL 行，不支持新旧 writer 混用。H2 组件验证不代表真实 MySQL 方言与锁行为已经验收。

下一批将复用原生 ReactAgent 的 ModelInterceptor，在真实 handler 前持久化派发、在 handler 后保存公开响应，并验证 checkpoint 丢失时响应复用。耐久工具动作配对、消息投影与观察确认、累计预算、目标终评、正式报告及客户端仍需后续实现；本批不声明完整探索链路已交付。
