# Issue #62 公共链路交付与验收

实施基线：`10d217e`（PR #197）。本次沿用 Issue #62 已确定的 Plan + 局部 ReAct + 结构化报告方案，将既有组件接入公共入口；不替换原架构，不重新实现 E125/E126 的业务 Skill。

当前状态：公共链路及通用结果页代码已完成，最小必要后端定向验收与前端静态检查已通过，等待下述合并记录登记。真实环境与视觉验收保持开放；不得把静态实现或 H2/脚本模型结果当作真实环境验收。

## 实际连接

| 原计划范围 | 本次连接 |
| --- | --- |
| 公共规划 | 原始提问持久登记 → 原生结构化需求提取 → 可信对象、期间、能力校验 → 原生规划 → 既有业务 Graph；多目标全部保留，未覆盖要求显式记录。 |
| 固定及局部探索 | 统一业务 Profile 复用统计 Tool、枚举、下降筛选和维度 Skill，并装配 Spring AI Alibaba 原生 ReAct 与既有调用、观察及恢复账本。 |
| 自动恢复 | 定时扫描短引用，重入统一准入；同 Run 去重，按原身份恢复统计任务；未知模型调用不会重新发送。 |
| 跨轮及改版 | 进度纯读，继续只唤醒原请求；新期间、对象或要求使用关联的新 Run；可信探索信号经过耐久规划和原子 revision 接纳。 |
| 报告 | 实际证据组装 → 耐久解读 → 逐目标 DELIVERY 评估 → 发布并绑定；同版读取、分页、历史与导出。 |
| 通用结果页 | 按 block 类型展示指标、图表、表格、解读、限制、建议和完整结果；按目标顺序图文穿插，保留旧投放和风险 Agent 路径。 |

## 修复的连接问题

- 原规划菜单的空参数 schema 缺少显式 `required: []`，导致真实规划校验拒绝。
- 冻结输入与持久化对空字段的序列化方式不同，恢复后同一请求被判为输入漂移；统一格式，未放宽权限校验。
- 业务证据条件误用了保留参数键；改为完整冻结查询摘要，范围和期间仍经可信输入校验。
- 原生探索提示包含不可直接 JSON 序列化的时间对象；提示只携带引用，完整期限仍由后台固定元数据控制。
- 报告重读将相同 JSON 数值的 Java 整数类型差异误判为内容变化；使用规范 JSON 比较，避免无意义的新版本。
- 明确继续没有重新唤醒暂停任务，以及模型超时可能过早清除实际回调状态；分别改为可信唤醒与真实回调退出时清理。
- 已登记请求缺少能力声明、或卸载新版 Profile 后可能进入旧 Graph；常驻身份门阻止降级，不因数据库异常放行。
- 同 Run 的原请求、规划引用可能被重复扫描；公共运行只调度原引用，并按 Run 限制同时推进。
- 反复授权时重复解析不可变计划和 Skill，放大完整链路耗时；只缓存有界的不可变解析结果，权限、期限和任务状态仍即时检查。
- 读取既有证据时错误地重复执行 Skill 文件路径检查；只读证据使用冻结定义及当前权限/成员版本，真正执行仍校验当前文件与固定版本是否一致。

## 配置及迁移边界

- 新链路通过 `campaign-plan-v2` Profile 和客户端 `campaign-response/v2` 声明启用；本次不修改本地运行配置，不启动应用。
- 配置现有 `short-link.agent.campaign-statistics.process-domain` 和可信 `dependency-skills-root`，沿用既有单实例进程身份及容量配置。
- 在现有迁移基础上顺序应用 `V20260923_3__campaign_conversation_turn.sql`、`V20260924_2__campaign_due_work.sql`、`V20260924_3__campaign_public_request.sql`、`V20260924_4__campaign_report_synthesis.sql`、`V20260924_5__campaign_replan_model.sql`。本次未对任何运行中的数据库执行迁移。
- 已登记身份独立于 Profile 存在；停用新版运行器不允许把已开始的请求交给旧 Graph 重做。
- 取消是对已有 Plan 的逻辑停止与旧回调隔离，不声称远端统计进程已物理退出；实际未决任务遵循原有回收期限。
- 跨 revision 采用仅限经真实消费关系、当前权限和冻结查询校验的 FIXED/FROZEN_SET 原任务。CURRENT_QUERY、ReAct CALL 和 Skill 内部任务不得假装兼容或另提交替代任务；不兼容的改版应拒绝。
- 导出包含完整的持久报告、明确标注的表格预览和固定版本完整结果入口；不把任意大小的明细一次拼入 JVM 内存。

## 验证范围

仅执行必要后端定向用例，使用脚本 ChatModel、H2 和现有模拟网关。前端仅执行定向 ESLint、格式和协议/组件静态检查。未启动 Docker、应用、浏览器或真实模型，未运行前端构建、前端测试或全仓回归。

### 风险与实际定向证据

只记录对应方法最终结果；修复重跑与旧证据不重复相加。日志保存在本地忽略目录 `.work/`，不提交模型数据、数据库日志或构建产物。

| 风险条款 | 定向用例 | 关键断言 | 实际结果 | 未覆盖范围 |
| --- | --- | --- | --- | --- |
| 多目标遗漏、对象/期间漂移 | `CampaignBusinessPlanFactoryTest`（4） | 多条依赖链及独立查询保留，跨组借用/错日期/错维度拒绝，解读与建议绑定真实数据 | 通过 | 自然语言真实模型召回率 |
| 下游重取全组、独立目标重复执行 | `CampaignPublicBusinessRuntimeTest`（1） | 原始问题经公开登记、脚本原生规划、真实依赖 Graph；比较→下降集合→省份+设备联合下钻读取真实上游；独立查询不重跑；五个原 job 完成后生成、读取和导出报告 | 通过，16.51 秒 | 真实统计服务、模型及大数据量性能 |
| FIXED/原生探索装配偏离既有合同 | `NativeDimensionChangeSkillTest` 受影响原生方法（1）、`CampaignDependencyAnalysisArtifactAuthorizerTest`（2） | 生产探索工厂复用原生 ReAct/统计恢复；动态结果证据使用实际来源和当前权限 | 通过 | 真实模型行为与远端时序 |
| 重试换身份、上下文丢失 | `CampaignPublicRequestStoreTest`（2）、`JdbcCampaignConversationTurnStoreTest`（3）、`CampaignPublicRequestServiceTest`（4） | 首次期限和请求冻结；三轮上下文保留；进度不取数；继续唤醒原请求；改变期间/范围创建关联新 Run；取消不新增查询 | 通过 | 真实多实例并发、远端物理取消 |
| 已开始请求降级重做 | `DefaultAgentRunHarnessTest` 两个新增方法 | 去掉客户端能力或运行器不能进入旧 Graph；缺表不缓存、数据库异常拒绝放行 | 通过 | 实际升级部署操作 |
| 恢复重复提交、占用提前释放 | `CampaignDueWorkDispatcherTest`（4） | 同 Run 不同 workRef 不并发；原 Future 未退出不重发；未知结果阻断；已证实准入拒绝才退避重试 | 通过 | 应用进程重启、真实调度时序 |
| 规划尚未创建 Run 被当作成功 | `CampaignPublicDeliveryAdapterTest`（2） | UNKNOWN/REJECTED 保留精确身份；空计划缺信息返回 NEEDS_INPUT；无报告目标不显示已回答 | 通过 | 真实浏览器交互 |
| 需求及接管版本混淆 | `ReplanRequestTest`（1）、`JdbcReplanReceiptStoreTest`（1）、`CampaignReplanAdmissionBinderTest`（3）、`CampaignReplanPlanningHandoffTest`（5） | 可信探索信号版本绑定、不可变 receipt、候选与原 token/身份精确匹配 | 通过 | 真实 MySQL 锁竞争 |
| 改版重发原 job、未知模型重试 | `CampaignBusinessReplanServiceTest`（2） | 原生信号→耐久改版→consumer 接管→原 job 完成；新 revision 成功，统计仅一次提交、一次接收；旧 token 写入拒绝；未知规划结果不重调 | 通过 | 不兼容任务的物理迁移/远端取消、真实数据库并发 |
| 证据只读反复校验文件、优化放宽权限 | `CampaignDependencyAnalysisQueryAuthorizerTest#frozenEvidenceReadsDoNotExecuteChangedMethodsButStillRejectRevokedOrChangedMembers` | Skill 文件改变后仍可读原固定证据，真实执行拒绝；日期/成员/版本漂移及撤权均拒绝；未缓存实时授权结论 | 通过 | 真实操作系统/大规模读取性能 |
| 解读重复调用或编造原因 | `CampaignReportNarrativeSynthesizerTest`（原 3 + 新 1） | 模型在锁外执行；只调用一次；未知结果不重发；外来证据拒绝；授权旧版本证据可用，撤权重放拒绝；原因仍未知 | 通过 | 模型分析质量与事实判断能力 |
| 部分完成误报完整、发布不原子 | `GoalAssessorTest`（8）、`CampaignRunReportPublicationCoordinatorTest` 新增方法 | 每目标据实终评；部分报告可在同计划版本升级且保留历史 | 通过 | 真实数据库事务隔离 |
| 历史/分页/导出混入新数据或越权 | `CampaignReportDeliveryServiceTest`（5） | 同版块和两页结果一致；旧计划封存可读；当前授权采纳旧证据；撤权拒绝；过期记录跳过；发布重放不新增报告版本 | 通过 | 真实 MySQL 迁移、保留期运维 |
| 公开端点信任客户端身份 | `AgentChatControllerTest` 新增方法、`AgentControllerTest` 两个方法、`AgentRemoteServiceFeignTest` 新增方法 | continuation 与能力透传；读取使用服务端当前主体/认证版本；缺认证版本零调用；精确报告/页身份传递 | 通过 | 真实网关与登录态链路 |
| 前端按提示词分裂、未知协议伪成功 | 本次变更文件的定向 ESLint、Prettier 与协议/组件静态核对 | 统一七类块；受控图表；逐目标状态；未知合同明确报错；GET 轮询不会自动 POST 重跑；保留旧投放与风险路径 | 静态检查通过 | 构建、浏览器、视觉、屏幕阅读器实际操作 |

最终三个受影响方法（公共 Graph、原 job 接管、只读校验）在 `.work/campaign-public-runtime-completion-tests.log` 中全部通过。其余最终结果分布于 `campaign-public-delivery-first-tests`、`campaign-public-delivery-integration-tests`、`campaign-public-delivery-repair-tests`、`campaign-public-delivery-connected-tests`、`campaign-public-admin-tests`、`campaign-public-final-contract-tests`、`campaign-public-replan-history-repair-tests` 日志。部分历史运行整体失败，表中只采用各方法修复后的最终通过结果；没有把失败的 Maven 批次写成整体成功。未受影响的已通过方法没有重复执行。

### 交付结论与合并记录

- 代码交付：原 Issue #62 的公共链路和前后端实现完成；默认 Profile 仍关闭，未经配置不会迁移已开始任务的运行方式。
- 后端验收：本次新增连接点及受影响失败分支已通过；既有 E125/E126 证据复用，未重跑全部历史组件。
- 前端验收：定向静态检查通过；未构建或启动浏览器，不能据此声明视觉和交互实测通过。
- 真实环境：MySQL 隔离/迁移、跨服务取数、实际重启部署和真实模型质量待验，Issue #62 保持开放承载这些验收项。

后端与前端合并链接在 PR 创建并确认合并后登记。
