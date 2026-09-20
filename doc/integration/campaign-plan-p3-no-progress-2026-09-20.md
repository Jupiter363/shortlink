# P3 已观察请求的重复提议停止 · 2026-09-20

关联 Issue #62，接续 E39。此前 CALL 身份包含 MODEL turn／toolCallId；模型换一个调用 ID，再提出相同查询，就会得到新的 CALL 和统计请求。本批为明确注册的可复用只读能力增加耐久检测，避免重复取数。

## 适用范围

只判断同 Run、revision、Step 中冻结配置、执行器版本和规范参数完全相同的请求。JSON 键顺序、turn、toolCallId、attempt 不制造新请求；真正参数变化继续按原合同调用。不同自然语言意图、不同 Artifact 身份、跨 revision 采用或新鲜数据请求不在本批的等价判断范围。

重复判定必须读取原实际 CALL 与已 OBSERVED 的完整收据，重新检查当前授权、期限、hash，并确认全部具名输出已经存在于当前 MODEL 的真实输入。只有模型已经拿到原事实、相同请求不会提供新证据时，才记录 `EXPLORATION_NO_PROGRESS`。没有产物、产物不完整或没有可见性证明，不能伪装成复用成功。

被拒绝的提议保留原 MODEL、源 CALL 和产物引用，形成唯一 `executed:false` Tool 配对后终止；不创建新的业务 CALL、child、job，也不占用新的 CALL 执行额度。原 Artifact producer 保持不变。停止记录与状态同事务，重开不删除或重置；累计记录按 Run 保存，跨 revision 不据此自动采用旧结果。

正常 WAITING、原任务 READY 接收、原 Skill continuation 和 checkpoint 丢失重放不算新提议。模型与 CALL 的现有 Run 累计预算继续生效。策略显式装配；旧构造器不依赖新增 migration 15，也不改变原配置 hash。

## 最小验证

3 个定向后端方法首次通过，0 失败／错误／跳过；日志 `.work/exploration-no-progress-tests.log`。仅两个新方法及一个受影响的旧 Driver 主例，使用实际 SAA ReactAgent、H2 和脚本模型／网关。

| 用例 | 验证内容 |
| --- | --- |
| `ExplorationNoProgressTest#reorderedExactProposalStopsBeforeAnotherCallAndPreservesOriginalEvidenceAcrossReopen` | 真实 Skill 两期取数完成后，模型换 JSON 键序和调用 ID 重复请求；检查 model=2、CALL=1、submit=2、pageRead=2、stop=1。实际原生 checkpoint 和后端规范消息各有唯一拒绝配对，引用原具名产物；同活 permit／空 saver 重开不重做，撤权拒读。真实 Step 终态退出及新 writer 只读取原阻断和计数，没有伪造新 Step 许可。 |
| `ExplorationNoProgressTest#changedApprovedMetricCreatesANewCallWhileItsOriginalWaitingJobsResumeWithoutFalseProgressStop` | PV 改 UV 形成两个合法 CALL，四个原统计任务接收后分别续接；model=3、submit=4、pageRead=4、stop=0。等待和重开不重提，最终保持候选状态，不冒充 Step／Goal 成功。 |
| `PersistentExplorationDriverTest#nativePlanDriverResumesTheOriginalSkillAndSettlesVerifiedOutputsBeforeAdvancingTheTypedConsumer` | 受影响旧 Driver 原生主链兼容；未装配重复策略时不需要 migration 15，不改变原会话配置。 |

## 保留边界

这是精确重复的无进展证明，不是任意自然语言同义识别，也不是跨目标或跨版本缓存。本批运行验证覆盖同 revision 的持久重开；Run 级计数实现不按 revision 分区，但没有新增跨 revision 停止累计测试，不能据此宣称重规划已验收。重规划、实际新收集代次、混合容量／未知提交、生产请求入口与正式报告继续按总计划推进。仅进行允许的后端 fixture 验证，不启动 Docker、应用、真实模型或浏览器。
