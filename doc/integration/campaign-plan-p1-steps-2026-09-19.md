# P1 第三批：步骤输出与持久化 Graph 驱动

跟踪 [Issue #62](https://github.com/Jupiter363/shortlink/issues/62)。前两批恢复协议与底层账本分别见 PR #65、#66。本批把冻结计划、步骤状态、具名输出和原生 Graph 接起来；组件仍未注册到生产入口。

## 执行与续接

- `FrozenCampaignRun` 在 Run 定义中固定 Plan、输入集、覆盖评估与运行器／拓扑版本。恢复读取原定义，拒绝未知协议、身份不匹配、重复字段和尾随内容，不读取会话中的最新参数。
- `JdbcCampaignStepStore` 保存完整步骤集合、依赖、允许／必需输出、执行次数与真实回调状态。领取 Run 和步骤均校验推进令牌，旧回调未退出时不能领取新执行权；重建对象不会清空回调标记。
- `PersistentPlanDriver` 驱动已有 Spring AI Alibaba `NativePlanGraph`，从 JDBC 判断步骤是否就绪。A 等待时，依赖它的 B 保持未执行，独立的 C 继续；重建驱动后完成项不会重跑。
- `CampaignStepExecution` 在实际调用前保存稳定 Action／Child／requestId 和原请求。仅 PREPARED 可以首次派发，已完成、等待或结果未知的子调用只返回原记录。独立恢复协议负责对账，不由这个入口重提请求。
- 可信执行器必须在每个真实 I/O 前调用 `IoBoundary.beforeIo()`；边界重查 Run、步骤、子调用以及已绑定范围／Artifact 的当前授权和期限。取消后晚到的 jobId 只保留旧事实，不能发布成功输出。
- 子结果耐久接收后，协调方显式刷新 WAITING。只有全部已登记子调用 READY、没有活跃回调时才能推进；权限或输出合同失败不会被刷新成可重试任务。

## 结果绑定

- `INPUT` 只取冻结输入；`STEP_OUTPUT` 只取当前 Plan 中声明依赖且已 SUCCEEDED 的指定端口；`ARTIFACT` 只取冻结引用。三者没有“找不到就猜一个结果”的回退。
- 可信 `ArtifactContractRegistry` 对具体 Artifact 类型、schema 版本和 ONE／MANY 建立唯一映射，并校验真实载荷与质量信息。MANY 是逐项校验的顶层数组，空数组是合法结果；包含 rows 的对象应注册为 ONE envelope。
- 各执行器注册普通 Java 输入／输出策略，负责其范围、期间与质量要求，可以明确支持多范围；模型不能通过参数放松这些要求。没有新增 Skill DSL 或 JSON Schema 解释器。
- 在步骤成功前，检查未知／必需端口、真实类型、载荷和策略；同事务再次检查 Artifact 元数据权限／期限及所有子调用完成状态，再发布具名输出与 SUCCEEDED。完整 payload 不进入步骤表或 Graph 检查点。
- 输入载荷只在本次执行期间持有，结束清理绑定层引用。可复用原 Artifact，但保留其生产者、原范围、质量和 provenance，不能改写成新生成的证据。步骤 SUCCEEDED 不等于目标 ANSWERED。

## 验证

最小定向测试使用 H2、MemorySaver 与注册的确定性执行器替身，不启动 Docker、应用服务或真实模型。最终 **23 个不同用例全部通过，无失败、无跳过**。

| 测试类 | 数量 | 覆盖 |
| --- | --- | --- |
| JdbcCampaignStepStoreTest | 7 | 冻结定义、CAS、依赖、原子输出、真实等待、未知结果、取消／改版 |
| StepBindingsTest | 7 | 类型与真实载荷、上游端口、当前授权与到期、空集合、范围／期间策略 |
| JdbcCampaignArtifactTest | 4 | 本次 readArtifact 复用元数据检查后的既有授权／完整性合同 |
| PersistentPlanDriverTest | 5 | 实际 Graph→Driver→JDBC 续接、错误输出、取消晚到、不可用执行器、撤权 |

前 18 项与最后 5 项分批各执行一次，都首次通过。没有重复执行上一批已通过的原生 Graph、Run 账本及跨服务恢复测试，也没有运行全模块测试。

## 尚未完成的门槛

- P1 仍需生产进程死亡确认与恢复协调、最小目标状态／可用结果说明及入口装配。不能将重建 Store 的测试称为真实进程崩溃恢复。
- 本批支持注册的 FIXED Tool／Skill 执行器边界，尚未登记生产业务适配器。整个计划存在不可用执行器或 REACT 步骤时，在首次派发前拒绝，避免只执行一半才发现能力不可用。
- 局部探索仍按 P3 接原生 ReactAgent 与持久化账本、派发门控、预算及进程准入，不新建自定义 ReAct 循环。现有原生组件没有被替换。
- CURRENT_GROUP／FROZEN_SET、全量分片与范围证明、远端结果释放及确定性组合按 P2 推进；混合重规划与最终报告仍属 P4–P5。
- SQL 迁移仅在后端测试加载；没有执行生产迁移、开新路由或更改前端。客户端状态兼容完成前继续关闭新入口。
