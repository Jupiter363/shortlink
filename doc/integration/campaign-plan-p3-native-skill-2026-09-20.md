# P3 原生 Skill 的具名多输出观察 · 2026-09-20

关联 Issue #62，接续 E36。将实际下降筛选 CALL 接入现有 Spring AI Alibaba ReactAgent，继续复用其模型与工具调度。

## 实现范围

原生 ToolCallback 使用真实 CALL 许可调用已注册的下降筛选算法，每次子请求／计算受原生取消与耐久资格约束。等待消息明确引用 Skill 调用；它不是某个远端 job，也不是任意第一个返回的 Artifact。

耐久观察以真实 invocation WAITING／COMPLETED 为准。统计子任务 READY 之后，服务端仍需续接原 Skill 并封存具名输出，才能让下一轮模型继续。最初已投影的 PENDING Tool 消息保持不变，完成后追加唯一可信 READY 观察；尚未投影 PENDING 的情况直接提供 READY。

完整输出身份由后端合同读取与授权，所有具名 Artifact 纳入下一轮 MODEL 的冻结输入。内容按有界事实预览投影，保留 OBSERVED_ONLY、完整性及缺口说明，不将预览、产物齐备或模型候选当成全部用户目标已经完成。

本批保持旧统计单输出路径及默认构造行为；新 Skill 观察仅在明确装配对应存储与迁移后开启。生产 Driver REACT、全目标验收、报告与客户端交付仍未开放。

## 验证

2 个定向后端方法首次通过，0 失败／错误／跳过。日志 `.work/native-skill-observation-tests.log`；未运行真实模型、真实数据库、Docker、应用或前端测试。

| 用例 | 关键断言 |
| --- | --- |
| `NativeDeclineSelectionSkillTest#nativeSkillResumesOnlyAfterNamedCompletionAndRetainsOnePendingPairAndOneReadyObservation` | 真实 SAA ReactAgent／H2 MODEL／CALL 调用真实下降筛选，PENDING 只引用 Skill，无虚构 job。空 saver 重开、仅一期 READY、两期 READY 但 Skill 未完成时均零额外模型调用。新 Step／原 CALL attempt 2 完成双输出后，空 saver 重开生成唯一 PENDING Tool 和 READY User；第二个 MODEL 同时读取真实 -7／+2、OBSERVED_ONLY／UNVERIFIED 与原限制原因，冻结输入包含原 scope 和两个输出的真实 metadata。再次重开、撤权拒绝及恢复均不重调模型／统计。总计 model=2、submit=2、pageRead=2。 |
| `NativeStatisticsExplorationToolTest#realStatisticsCallReceivesAllPagesAndNewWriterWithEmptyCheckpointContinuesWithoutResubmission` | 原单统计 Tool 的实际原生调用、完整分页及新 writer／空 saver 恢复保持兼容；旧构造不依赖 migration 13。 |

新增 migration 13 只保存 receipt 类型、completion ID 与完整输出集哈希。`readCompletion` 与首次完成共享真实 LOCAL 全输出校验，不依赖旧 Step 继续活动。投影默认每个产物最多预览 12 行，明确返回／省略数量与预览完整性；期间保留真实引用，不推测日期。

本例覆盖实际单片两期异步 Skill；空范围同步完成、多片多阶段等待与容量／UNKNOWN 混合恢复未在本批测试。脚本模型只证明协议和实际输入，不证明真实模型语义质量。对应 P3-02／03／04／07 和 R03／R15；结构化终局候选、后端完成条件验收与 Driver 组合继续推进。
