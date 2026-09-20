# P3 真实下降筛选 Skill 的 CALL 执行 · 2026-09-20

关联 Issue #62，接续 E35。将现有 `decline_selection/1` 的两期统计、逐分片比较和双输出发布，接入真实 MODEL 发起的 CALL；不创建伪固定 Step 或额外模型循环。

## 实现范围

实际 CallSpec、源 MODEL 的单一 toolCall／参数与批准输入共同确定绑定。只允许真实探索 Step 暴露的冻结 INPUT，以及源 MODEL 已可见且当前有权读取的 ScopeArtifact。执行前核验注册 policy／capability、方法文件 pin、发布范围及当前权限；每次统计 I/O、LOCAL 计算和结果接收仍走原资格边界。

固定 Step 与 CALL 复用一个下降筛选执行循环和同一 Publisher 算法。CALL 集合／子任务／输出使用原 callId 的稳定身份，所有子任务归属该 CALL Action；新 attempt 不改变原请求和发布身份。等待精确原任务，齐备后以新 Step／CALL attempt 恢复，真正封存 SelectionStore 后才完成 Skill。

Publisher 的最终输出声明可在 head 尚不存在时冻结，但不伪造 head 或 LOCAL 输入。SelectionStore 的 CALL append／seal 在同事务内验证父 CALL 资格和同 Action 来源；旧 token-only 入口不能绕过这些要求。

本批不注册 Native Skill tool，不把多个 job 包装成虚构 jobId，也不把具名双输出降成任意单 Artifact。原生 PENDING／READY 多输出协议和 Driver 装配继续推进，生产 REACT 仍关闭。容量未受理或 UNKNOWN 不能伪装成普通等待；其 CALL 恢复协议尚未覆盖。

## 验证

2 个定向后端方法首次通过，0 失败／错误／跳过。日志 `.work/decline-call-execution-tests.log`。未启动 Docker、应用、真实数据库、真实模型或前端测试。

| 用例 | 关键断言 |
| --- | --- |
| `DeclineSelectionCallTest#actualSkillCallWaitsForBothOriginalJobsThenPublishesReadableDeclinesWithoutResubmission` | 同 Run 真正发布两成员范围，真实 MODEL／CALL 提交两期 LINK_METRICS。仅一期返回保持等待，当前撤权零额外读取；新 writer／Step 恢复原 CALL 后发布真实分页链和双输出。实际索引选出 link 1 的 10→3（-7），link 2 的 2→4 仅在证据中保留；UNKNOWN 的限制没有抹去。submit=2、pageRead=2、MODEL响应=1，所有 Skill child 属于原 Action。撤销 CALL 后旧入口及 CALL append／seal 均拒绝。 |
| `DeclineSelectionSkillTest#nativeSkillWaitsReceivesReleasesAndPublishesAll501CandidatesWithoutResubmission` | 原固定 Skill 的 501 候选、两期分页、等待恢复、释放及完整发布仍通过，身份与共用算法兼容。 |

这验证了实际业务 CALL 组件，仍未验 Native 模型观察、多个等待阶段与容量／UNKNOWN 混合恢复、真实 MySQL 或生产 Driver。对应 P3-02／P3-03／P3-07、R15 的首个非探索 Skill 适配；后续继续接入原生消息和整体完成判定。
