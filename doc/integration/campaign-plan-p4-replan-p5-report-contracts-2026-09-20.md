# P4 重规划触发与 P5 报告评估合同 · 2026-09-20

本批在 E47 之后补齐两个内部后端合同：运行中的探索只能提出证据驱动的重规划请求；报告发布前由服务端按固定规则评估每个目标，并将数据块、分析块和证据清单交给通用前端渲染。两者都没有注册生产路由、调度器或模型工具。

## 重规划请求

`planning.ReplanRequest` 保存基线 Plan、PlanningAssessment、原始 evidence IDs、typed 新证据、未满足 requirement 和公开理由。构造时要求至少有一条新证据或已声明的 planning gap；证据 ID 和 artifact ID 不能与基线重复，未满足 requirement 必须属于当前 assessment 的 gap。`assess` 只接受同一 run/plan/inputSet、revision 严格递增、原 goals 和 requirements 完全相同且 revision-insensitive Plan hash 发生变化的候选。返回带 reason code、基线/候选 hash 和原目标/需求 hash 的不可变结果；仅递增 revision 的等价计划会被拒绝。

该合同不会直接修改 RunStore 或 Graph。后续编排必须先耐久保存请求和候选，再在同一个 REQUIRED 事务内校验、编译新 Graph、接管兼容 consumer，成功后才 CAS 切换 revision；编译或采用失败不能调用当前 `revise` 产生半个新版本。

## 报告评估与发布

`report` 包新增固定的 `ReportDraft`、`ReportSection` 和 `ReportBlock`。Block 支持 metric/chart/analysis/table/limitation/recommendation/result-link，模型文本不能设置最终目标状态；完整数据块或授权的完整结果入口才满足 DELIVERY。`GoalAssessor` 读取服务端 PlanningAssessment、逐 requirement observation 和真实 Draft，输出 `PENDING/ANSWERED/PARTIAL/NEEDS_INPUT/UNSUPPORTED/UNAVAILABLE/CANCELLED`，将未知证据、因果不足和交付缺失保留为 limitation。每个 goal 独立评估，某个 goal 缺失交付不会污染其他 goal。

`CampaignReportPublisher` 先检查固定 report/plan identity、客户端能力和所有 evidence refs，再通过 `EvidenceReader` 验证 READY、checksum、read contract 和 retainedUntil，计算 versioned EvidenceManifest 和 immutable report revision。内存 store 只作为合同边界，尚未替代生产 Artifact/报告表、历史授权或清理 CAS。

## 最小验证

| 测试 | 结果 |
| --- | --- |
| `ReplanRequestTest#evidenceGatePreservesOriginalQuestionsAndRejectsRevisionOnlyNoOp` | 通过：新证据候选可接受，原 goals/requirements hash 保持；仅递增 revision 的等价候选、重复证据均拒绝。 |
| `CampaignReportPublisherTest#missingFirstGoalDeliveryDowngradesOnlyFirstGoalAndPublishesFixedEvidenceManifest` | 通过：第一目标没有完整结果块时为 PARTIAL/DELIVERY_MISSING，第二目标仍 ANSWERED；reportId/revision 与 manifest 固定，所有证据来自 READY reader。 |

本批 Agent 定向 Maven 运行 2 个方法，0 失败/错误/跳过；没有启动 Docker、应用服务、真实数据库、真实模型、浏览器或前端测试。后续仍需实现重规划耐久 receipt、事务内 Graph/consumer 发布、ReportStore 生命周期、历史/导出授权、Admin/客户端接线和真实 MySQL 验收。
