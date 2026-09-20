# P3 结构化候选与后端完成条件验收 · 2026-09-20

关联 Issue #62，接续 E37。模型的终局文本先解析为闭合候选，再从真实 MODEL 输入中解析证据，由版本化服务端代码检查条件。局部 COMPLETE 仍不等于外层 Step 成功或用户目标 ANSWERED。

## 实现范围

`exploration-candidate/v1` 定义 COMPLETE、NEEDS_INPUT、REQUEST_REPLAN 和 NO_PROGRESS。公共摘要与请求说明不充当表达式、Plan 或检查器；COMPLETE 只能绑定已存在的 ARTIFACT。未知字段、重复键、尾随内容、类型转换和跨分支字段被拒绝，协议大小超限明确报错，不截断分析内容。正式报告与完整分析不存放在此短候选中。

后端绑定当前 Run／Step／FINAL MODEL 身份、原响应 hash 和实际冻结输入，要求没有活动子回调、CALL 或未完成子项。全部必需输出要满足注册端口、类型、schema、scope、period、当前权限、期限与真实 metadata；模型不能声明新的条件或替换规则。候选与验收收据在同一事务写入，重开再次校验原来源和条件，不能用新规则悄悄升级旧结果。

新增两个具体检查器：下降筛选覆盖读取真实已封存 pair；采集完整性独立读取产物质量。覆盖已完成与采集 UNVERIFIED 可以同时成立。冻结策略要求采集完整时，UNKNOWN 导致拒绝，不会被当作完整或零数据。

验收收据仅记录来源 hash、规则指纹、具名引用、条件状态及原因；完整模型文本仍只有原 MODEL 响应。非法候选、缺输入、重规划请求和模型报告无进展均形成明确耐久终态，不重复调用模型。NO_PROGRESS_REPORTED 不是机制已经证明无进展；本批不执行重规划，也不改变 revision。

## 验证

3 个定向后端方法通过，0 失败／错误／跳过；日志 `.work/candidate-assessment-tests.log`。首次编译发现跨包调用了包内编码方法，已改为本组件的稳定排序编码，随后所选测试首次执行通过。只选择 2 个新方法和 1 个受影响旧方法，未运行全模块回归。

| 用例 | 验证范围 |
| --- | --- |
| `ExplorationCandidateAssessmentTest#realNamedSkillEvidenceCompletesOnlyRegisteredCriteriaWhileUnknownQualityRemainsRejectedAcrossReopen` | 两个独立冻结策略分别运行真实 Native Skill：覆盖条件 MET 时 COMPLETE；增加采集完整条件时 UNKNOWN／REJECTED。原 Step 始终 RUNNING、输出为空；重开同源收据不重调模型／统计，旧 writer 与当前撤权拒绝。每个场景 model=2、submit=2、pageRead=2。 |
| `ExplorationCandidateAssessmentTest#terminalRequestsAndMalformedCandidateAreDurableWithoutExecutingCapabilitiesOrRevisingThePlan` | 缺输入、重规划、无进展及非法字段四种真实原生终局响应；每个场景模型一次、业务调用零、revision 不变、重开不重复。 |
| `NativeDeclineSelectionSkillTest#nativeSkillResumesOnlyAfterNamedCompletionAndRetainsOnePendingPairAndOneReadyObservation` | 受影响旧构造器与原生多输出 Skill helper 兼容；未装配候选存储时仍保持原协议，不依赖 migration 14。 |

本批使用实际 Spring AI Alibaba ReactAgent、H2 和脚本模型；未启动 Docker、应用、真实模型、真实数据库或前端测试。生产 Driver REACT 仍关闭；接下来的 Step 输出发布、外层状态映射、真正无进展机制及逐目标报告验收继续按总计划推进。本批证据对应 P3-03／06、V06，不代表 Goal／报告或生产开放门槛已完成。
