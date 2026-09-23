# 投放分析公共链真实验收记录（2026-09-24）

**结论：本轮复合分析公共主链、真实局部 ReAct、所选后端用例及 1600px 宽屏视觉复验已通过；窄屏与浏览器下载文件仍待验。** 复合 Run `campaign-run-b9c90398…15ea0` 已完成 **6/6 步骤**，**7 个唯一异步 query job 全部 SUCCEEDED，attempts 均为 1**，下降集合真实传入两期省份与设备联合下钻；报告 `report-a54e1d81…c2a7b5d` 的 **revision 2 为 READY、3/3 目标 ANSWERED**。另一个单日只读探索 Run `campaign-run-dc4fe737…16cf2` 实际冻结为 `REACT`，完成两轮模型、一次统计工具调用，报告 **2/2 目标 ANSWERED**。早期需求解释、规划准入、冻结查询 DTO 及恢复错误的中间记录仍保留，不作为当前运行结果。

代码修复已进入本轮真实链路；后端结论限于下列定向测试及现场场景。前端维度表直接展示原始 JSON 的问题已修复，并经内置浏览器宽屏复验；窄屏尚未单独复验。模型文字中的“两组卡方”错词、UNKNOWN 地域数据，以及尚未执行的真实撤权和多实例故障验收另列限制。本文不据此宣布所有能力、所有视口验收或 Issue 收尾全部完成。

证据目录：`.work/final-acceptance-20260924/`。时间按 Asia/Shanghai，状态以对应采集时刻为准。本文区分落盘文件与主代理的数据库／浏览器现场核验，不将现场观察伪装成已落盘日志。未附凭据、备份正文、原始 IP 或模型原始响应。

## 当前验收矩阵

| 项目 | 已核验结果 | 证据与边界 | 状态 |
| --- | --- | --- | --- |
| 本地基础设施、Docker 数据盘 | 基础设施及迁至 D 盘的 Docker 数据正常可用，真实链路已访问持久数据 | 主代理现场健康核验；未以重建空库或 fixture 数据替代。本次文档更新不启动或探测服务 | 已核验 |
| Flink 运行与状态延续 | 从原 checkpoint **3250** 恢复到 job `e62b205974fb32ec952287a853ae3569`；**6/6 tasks RUNNING**，新 checkpoint **3251** 完成、6/6 ack、0 failed；Connect 及 task RUNNING，消费位置推进 | `flink-restore-identity.json`、`flink-restore-final-status.json`、`flink-recovery-summary.md`。沿用原 epoch、build、group 和 checkpoint，不表示所有历史期间均完整 | 已核验 |
| Agent 数据库迁移 | 当前 **35 项迁移**，包含 `V20260924_6__campaign_public_request_cancellation.sql` 和 `V20260924_7__campaign_public_request_history_indexes.sql` | `migrations.json` 中 V7 为 applied，其余 34 项 previously applied。`migrations-initial-33.json` 仅是早期快照 | 已核验 |
| Analytics 控制库迁移 | 迁移 **005** 已执行；执行前保留 **102,786,692 字节**备份 | `analytics-control-before-005.sql` 的文件大小已核对，未读取备份正文；005 状态来自主代理现场核验 | 已核验 |
| 当前分组、两期明细与排名 | 冻结短链集合 **`[20003,20004]`**；09-13 两行 PV 分别 **2、1**，09-14 两行 PV 均为 **0**；独立完整排名为 **20003／PV 2／rank 1**、**20004／PV 1／rank 2** | 主代理现场读取本轮实际结果。零值是已返回的两行，不是缺失查询；不能混入全租户总量。早期直接结果另见 `campaign-report-37eb.json` | 本轮场景通过 |
| 真实模型、规划与依赖执行 | Run `campaign-run-b9c90398…15ea0`：**6/6 steps SUCCEEDED**；**7 个唯一异步 job 全部 SUCCEEDED、attempts 1，无重复 job** | 主代理数据库现场核验；独立排名与依赖分析均完成。执行账本的 **14 条 child 记录**不等于 14 个唯一 query job | 本轮场景通过 |
| 真实下降集合向联合下钻传递 | 下降结果 **`[20003,20004]`** 实际传到两期 `province+device` 查询，范围为 **FROZEN_SET** | 主代理核对上下游结果及冻结请求；使用真实选中成员，没有用当前全组维度查询代替下游范围 | 本轮场景通过 |
| 真实局部 ReAct | Run `campaign-run-dc4fe737…16cf2` 冻结计划只有一个 **`REACT / statistics-exploration`** 步骤；探索账本记录两轮模型（先工具观察、后终局）、一个已返回 CALL、一个唯一统计 job；步骤 SUCCEEDED，正式报告 **READY、2/2 ANSWERED** | 主代理核对持久计划、exploration session/turn/call、Child 与报告；查询严格限于所选分组、09-13、浏览器维度。一个真实样本不代表任意探索能力都可成功 | 本轮场景通过 |
| 正式报告与逐目标状态 | `report-a54e1d81…c2a7b5d`：**READY、revision 2、3/3 ANSWERED**；revision 1 的 **2/3 部分完成**历史仍保留 | 主代理读取服务端报告及浏览器历史。报告 revision 不冒充 plan revision；旧报告未被新结果覆盖 | 本轮场景通过 |
| 完整结果分页 | 固定 **report revision 2、size 1**，两页依次返回 **20003、20004**，两页 **total 均为 2**，末页没有 cursor | 主代理经授权接口读取同一版本；没有将最新版本数据混入指定版本分页 | 本轮场景通过 |
| 历史、刷新及只读恢复 | 浏览器能读取 **revision 1（2/3）**；刷新恢复 **revision 2（3/3）**；读取前后 **public_request 1／child 14／report revisions 2** 均不变 | 主代理浏览器与数据库现场核验；本轮未复现早期 workspace HTTP 500，未新增请求、child 或报告版本。不是对所有撤权／失败恢复组合的全面验收 | 本轮场景通过 |
| 服务端导出内容 | 受控内部接口返回 Markdown，**22,704 UTF-8 bytes**；包含 **20003、20004、广东省、排名**；哈希见下文 | 固定版本服务端内容已核验；与浏览器下载文件核验分开记录 | 服务端导出通过 |
| 浏览器导出下载 | 已点击真实页面导出按钮，**未捕获下载文件** | 尚不能证明浏览器落盘文件的字节、内容、文件名或哈希与服务端一致 | 浏览器文件待验 |
| 继续与报告视觉 | 浏览器已验证继续沿用原问题、分析记录不重复；**1600px** 下维度表显示“**设备 Desktop／省份 广东省**”、基期 **PV 3／UV 2／UIP 2**、目标期 **PV 0／UV 0／UIP 0** 及 PV 占比；复杂原字段可在“完整字段”展开，表格局部横滚 | 主代理在真实内置浏览器复验；本轮局部 ESLint／Prettier 及 `npm run build` 通过。原始 JSON 直接展示缺陷已关闭，**窄屏未单独复验** | 宽屏与交互通过，窄屏待验 |
| 早期失败／遗留请求 | 早期请求已被拒绝或取消，其失败状态不冒充新 Run 的成功 | 主代理现场核验；旧需求映射和 provider 错误保留为中间证据 | 已区分 |
| 早期复合需求与恢复错误 | `REQUIREMENTS_INVALID`、非法规划准入、冻结请求反序列化及关联上下文恢复错误均保留中间证据；本轮复合执行与刷新读取已完成 | 下面分别列出修复后的定向日志；不改写旧失败请求为成功，也不把早期 `compound-request.json` 当成本轮成功证据 | 已由新运行验证主链 |
| 真实撤权及多实例故障 | 本轮没有执行真实权限撤销或多实例故障演练 | 定向身份隔离测试不能替代真实撤权、并发接管或分布式故障验收 | 未测 |

## 风险 → 用例／断言 → 证据

以下 selector 是日志实际执行的测试类；括号为该次选中用例数，不表示整个类或全模块已回归。中间失败与修复后通过分别记录，不累加成虚构的总通过数。

| 风险 | Selector／关键断言 | 已有日志与结果 |
| --- | --- | --- |
| 实际 Spring Jackson 无法解析严格合同 | `FrozenQueryScopeTest`、`QueryJobRecoveryControllerTest`、`GroupMembersPageTest`：合法内容能通过含 ParameterNamesModule 的 mapper；未知字段、错误数值及非法范围仍拒绝；输入错误返回 400 | `analytics-jackson-test.log` 的 scope 1 例通过；controller 由 `transport-jackson-test.log` 的 1 例通过覆盖；成员合同在 `native-options-test.log` 的 1 例通过。后两轮 reactor 另有失败，不标记整体成功 |
| 每个 build 单独校验 proof 耗尽 deadline | `JobClickHouseProofBatchTest`（3）、`JobClickHouseStreamTest`（5）：批量读取证明，保留缺失、重复、不匹配的拒绝；真实两天 560 build 的 proof 读取从 569 次降为 18 次 | `repair-regression-test.log`：API 8/8 通过；同日志 Agent 有中间失败，不能将整轮记为成功 |
| 原生模型默认 options、structured 合同或需求来源未接通 | `PlanningProposalEnvelopeTest`（2）、`CampaignInterpretedRequestTest`（1）、`CampaignPlanningIntakeTest`（1）、`DeepSeekSpringAiChatModelTest`（2）：覆盖需求来源与原生调用连接点；transport 的 mock HTTP 不等同真实云调用 | `final-connection-test.log`：所选 11 例全部通过、BUILD SUCCESS，另含 synthesis（1）、business factory（1）、session recovery（3）。真实模型可用另由 Run 37eb 完成证明 |
| 公共请求取消不能隔离迟到结果 | `CampaignInterpretedRequestTest`（1）、`CampaignPublicRequestServiceTest`（1）：来源合同及公共取消路径的定向断言 | `source-cancellation-retry-test.log`：2/2 通过、BUILD SUCCESS；不据此声称所有浏览器取消时序已覆盖 |
| due 推进、步骤结束与正式报告状态混淆 | `CampaignBusinessProfileTest`（1）、`CampaignPublicRequestServiceTest`（1），以及重跑的 `CampaignPublicDeliveryAdapterTest`（2）：依持久状态推进／读取，不用单一步骤完成替代整份报告完成 | `runtime-resume-due-test.log` 中前两类通过，但 Adapter 2 例错误；`due-status-retry-test.log` 中 Adapter 2/2 后续通过、BUILD SUCCESS |
| 多目标共享查询或证据消费关系错误 | `CampaignBusinessPlanFactoryTest`：共享冻结输入、Skill 结果复用及跨目标证据关系 | `shared-frozen-dependency-test.log`：1/1 通过；`cross-goal-history-test.log` 的 synthesis（1）、factory（2）通过；本轮 `skill-cross-goal-reuse-test.log` 所选 factory 3/3 通过、BUILD SUCCESS。真实复合结果另由当前 Run 验证 |
| 局部 ReAct 仅有脚本模型组件证据 | `NativeStatisticsExplorationToolTest`、`NativeDurableExplorationLedgerTest`、`NativeDurableModelBoundaryTest`：原 job 分页复用、冻结模型 options、响应后 checkpoint 故障不重复调用 | 既有 Surefire 对应各 1 例通过；本轮真实 Run `dc4fe737…16cf2` 又证明模型实际选择 `REACT`、一次工具调用和两轮观察，单查询真实链通过；更复杂探索和真实故障窗口仍未实测 |
| 刷新丢会话、关联原文误判或读取触发执行 | `CampaignSessionRecoveryServiceTest`：当前身份、合法关联上下文、精确当前问题、分页和只读读取；伪造关联及跨身份拒绝 | `linked-boundary-recovery-test.log`：recovery（6）、public service（2）、interpretation（1），9/9 通过、BUILD SUCCESS。现场验证当前报告历史／刷新读取，未重现早期 workspace 500；真实撤权未测 |
| 解释按指标重复查询，或把选中集合误解释为全组下钻 | `CampaignInterpretedRequestTest`（3）：单次 LINK_METRICS 含 PV／UV／UIP、比较复用、完整下降下钻场景、v2 精确保序去重；v1 冻结历史及原严格校验不变 | `interpretation-boundary-test.log`：3/3 通过、BUILD SUCCESS；只验证确定性合同，不声称任意提示词意图识别准确率均已达标 |
| 非法候选在 Run 登记后才失败，或修复候选绕过准入 | `CampaignPlanningIntakeTest`、`CampaignBusinessPlanFactoryTest`：严格解析后规范化，原模型回执不变，materialize／domain 校验后才接受 | `normalizer-test.log`：factory（1）、intake（2），3/3 通过、BUILD SUCCESS。`preacceptance-dependency-test.log` 的 intake 3 例及 public runtime 1 例通过，但 factory 1 例中间错误，**整轮不记成功** |
| 规划无效被误报为需要用户补条件 | `CampaignPublicRequestServiceTest`（2）、`CampaignPublicDeliveryAdapterTest`（1），另含 factory（3）：系统规划失败保留 reasonCode、FAILED／WAIT，不凭空生成 requiredInputs 或重新调用模型 | `complete-reuse-and-status-test.log`：6/6 通过、BUILD SUCCESS |
| Frozen statistics DTO 在生产 Jackson 配置下拒绝合法请求 | `AgentToolInternalMvcTest`（2）：ParameterNamesModule 下提交／recover-existing 保留原 requestId 与 scope，未知字段及类型伪造 400；`FrozenStatisticsScopeContractTest`（1）：真实 Controller → Facade → loopback wire 保留冻结范围及恢复身份 | `admin-frozen-scope-mvc-test.log`：3/3 通过、BUILD SUCCESS；本轮真实 7 个 job 成功提供生产链补充证据 |
| 公共入口无法完成真实业务 Profile 的依赖执行与报告连接 | `CampaignPublicBusinessRuntimeTest`（1）：脚本模型及真实后端组件的公共链集成 | `final-ranking-linked-test.log` 中该用例曾错误；`final-public-runtime-retry.log` 重跑 1/1 通过、BUILD SUCCESS。前者其它选中类通过不把失败 reactor 记成全通过 |
| 报告合成与证据块绑定不一致 | `CampaignReportNarrativeSynthesizerTest`（3）：报告叙述及额外块的受控合同 | `synthesis-extra-block-test.log`：3/3 通过、BUILD SUCCESS；本轮仍发现“两组卡方”错词，结构校验通过不等于模型语言无误 |
| 前端恢复、继续或报告投影无法发布 | 对应构建产物能够生成；本轮维度表再经局部 ESLint／Prettier、`npm run build` 和 1600px 内置浏览器复验 | `frontend-recovery-build.log`、`frontend-session-history-build.log`、`frontend-continuation-build.log`、`frontend-report-projection-build.log` 保留早期构建记录；最新表格修复与宽屏结果由主代理确认。窄屏及下载文件不能由构建代验，本次文档更新不重跑检查 |

原生 provider 的早期初始化／options merge 失败由 `native-provider-facade-test.log` 后续 2 例通过覆盖；该测试使用真实 provider 类和 mock HTTP。`cross-goal-history-package.log`、`shared-frozen-package.log` 的 BUILD SUCCESS 仅证明打包结果，不替代浏览器闭环。

## 数值、版本与导出口径

- 本轮范围为当前授权分组的冻结成员 `[20003,20004]`。09-13 的 PV 分别为 **2、1**，09-14 为 **0、0**，因此两条均进入实际下降集合。独立完整排名使用合计期间，分别为 **PV 2／rank 1**、**PV 1／rank 2**，没有将预览两行错误当成未经完整性校验的 Top N。
- 当前三个目标 **3/3 ANSWERED**、六个步骤成功和七个唯一异步 job 成功，是不同层次的计数。**child 14** 是执行记录数，不应写成十四次独立统计查询；读取这些结果前后的记录数没有增长。
- 独立的真实 ReAct 样本只有一个 `REACT` 步骤和一个唯一统计 job；解释被保留为第二个目标，复用同一结果，最终 **2/2 ANSWERED**。两轮模型与一个 CALL 的账本事实不能扩展为任意工具或 Skill 都已由真实模型覆盖。
- 当前正式内容绑定 `report-a54e1d81…c2a7b5d` **revision 2**。revision 1 的 **2/3** 部分完成内容可从历史读取；刷新显示 revision 2，不覆盖旧版本。指定 revision 2 的两页完整结果保持相同 total，末页没有下一页 cursor。
- 服务端导出经受控内部接口取得 Markdown，长度 **22,704 UTF-8 bytes**，SHA-256 为 `4d1bb38fe6d8c20a59fc3a23d3078c031c07c670d937680fc7914ce947910d05`，实际内容包含 **20003、20004、广东省及排名**。这是服务端固定版本导出的核验，**不是浏览器下载文件的哈希证明**。
- 早期直接 Run `37eb…` 的 **PV 3／UV 2／UIP 2、2 行**及 **8 个独立 blockId／6 个跨目标复用块**仍属于该旧报告证据，不能直接作为当前复合报告的 block 数或版本统计。三个目标完成也不证明任意复杂多期间、多对象或依赖任务均已通过。
- `data-readiness.md` 对 09-13／09-14 的 manifest 与 raw／geo proof 提供历史核验证据：每天 288/288 finalized 窗口、560 个唯一 build。不能将这两天扩展到未核验日期；恢复 Flink 消费不自动补齐所有历史 publication。
- 本次实际维度样本的未知地域计数为 **0**，未出现可用于验收 UNKNOWN 地域展示的记录。省份缺失、未知地域、样本局限仍必须按未来实际证据保留，不能据“广东省”已出现或目标 ANSWERED 就补全未知地域、提高数据完整性或声称已证明因果。

## 剩余限制与待验项

1. **窄屏视觉复核**：维度表原始 JSON 直接展示问题已修复，1600px 宽屏展示及“完整字段”展开、局部横滚已现场核验，不再作为已知视觉阻断。窄屏尚未单独复验，仍需确认表格、长内容与布局可读；不能将宽屏结论扩展到所有视口。
2. **浏览器下载文件**：页面导出按钮已点击，但未捕获下载文件。仍需检查实际文件及其内容／字节与固定版本服务端导出的一致性；本轮仅服务端导出通过。
3. **模型文本质量**：报告出现“两组卡方”错词，暂列非阻断内容问题。保留完整分析并修正措辞，不因 3/3 ANSWERED 宣称所有叙述准确或统计推断成立。
4. **权限与故障边界**：真实权限撤销、账号状态变更后的读取以及多实例并发／故障接管未做现场验收。已有身份隔离与恢复定向测试只能证明对应断言，不能将这些真实场景标为通过。
5. **交付状态**：本记录确认当前复合主链、固定版本读取／服务端导出、所选后端用例及宽屏视觉的结果；窄屏、浏览器文件和上述未测场景保持开放。PR 合并、Issue 关闭及全计划完成状态由主代理依据完整交付记录另行确认，本文不虚构这些动作。

本轮遵循最小必要定向验证，没有将现有日志描述为全仓回归、压力测试或全面分布式故障验收。
