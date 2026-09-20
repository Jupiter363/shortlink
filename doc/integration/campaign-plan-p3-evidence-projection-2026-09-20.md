# P3 统计事实的模型投影 · 2026-09-20

关联 Issue #62，接续 E32。统计任务已能耐久返回结果；本批让原生探索模型在继续时读取真实统计事实，而不是仅看到 Artifact ID。

## 边界

采用服务端版本化 `ExplorationArtifactProjection`，实现 `StatisticsArtifactProjection`。只对真实 READY child 的已校验 StatisticsJobPages 读取 manifest 与受限预览，保留当前 ACL、TTL、hash、页 checksum 和原始来源。未知类型维持引用模式，认识的类型若合同损坏则拒绝。

投影附在原有 READY 工具观察中；异步调用保留原 PENDING tool response，在唯一后续 READY user observation 中添加 `evidence`。业务字段不提升为 SystemMessage 或调用权限。原子模型请求仍冻结完整规范消息，恢复已经准备的 MODEL 使用原请求，不重新选择事实或重复查询。

投影实现／schema／限制的身份纳入探索 session 配置 hash，已有会话不能静默切换预览配置。旧构造器保持 reference-only 的原配置 hash 和观察 JSON，继续兼容已经持久化的引用模式。新模式按新运行的服务器配置显式选择，生产 Driver 仍关闭。

数据展示区分整窗指标、质量／近似标识、完整接收状态和有限预览。预览不是全部结果或全局排名；缺失指标保持缺失，不补零、不求和 UV/UIP。完整分页仍留在后端，不能把这一预览机制当作多页探索阅读、报告发布或模型分析质量的完整交付。

## 验证

2 个定向后端方法首次通过，0 失败／错误／跳过。日志：`.work/exploration-evidence-projection-tests.log`。只使用 H2、原生 ReactAgent、脚本模型与网关，没有启动 Docker、应用、真实模型、真实数据库或前端／浏览器。

| 用例 | 关键断言 |
| --- | --- |
| `NativeStatisticsExplorationToolTest#boundedStatisticsEvidenceRemainsDataAndFrozenModelRequestSurvivesCheckpointFailureAndReauthorization` | 实际 DIMENSION_BREAKDOWN 501 桶分两页接收；模型读到整窗 PV=501／UV=13／UIP=1、PARTIAL／UNKNOWN、2 行预览及其余 499 行未展示的明确标识。PENDING tool 与唯一 READY user 配对；恶意 browser label 只作为数据，不进 SystemMessage，内部来源树及其余桶不进入模型请求／checkpoint。实际 MODEL 响应持久后 saver 故障，新实例／空 saver 复用原冻结请求与响应。投影限制 2→3 的配置漂移被拒绝；撤权阻断，恢复权限可继续。最终 model=2、submit=1、status=1、page=[0,1]，仅 CANDIDATE。 |
| `NativeDurableExplorationLedgerTest#emptyCheckpointRecoveryReusesCommittedModelTurnAndCompletedToolBeforeContinuingDependentAnalysis` | 受影响的旧引用式三轮同步恢复保持兼容；MODEL 提交后 checkpoint 故障，恢复不重复模型／已完成 Tool，下一依赖仍正确推进。 |

默认投影为 12 行、65,536 UTF-8 字节，可由服务器配置，配置身份固定到会话；超限显式拒绝，完整结果仍留在后端。测试使用 2 行以确认预览与全量的区别，不是限制用户目标或结果行数。

对应 B05／R04 的上下文边界、G19 的业务字段角色隔离、P3-04／R03 的冻结消息恢复。此测试不证明模型能抵御所有提示注入，也不验证线上分析质量、跨查询可比性或完整报告交付。
