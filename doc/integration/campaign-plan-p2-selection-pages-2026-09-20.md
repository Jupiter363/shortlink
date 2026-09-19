# P2：完整下降集合与比较证据分页

日期：2026-09-20。关联 Issue #62，承接 E17 真实统计页对账与 E18 本地计算发布。

## 产物与语义

`DeclineSelectionPublisher` 使用实际范围凭证和两期统计 Artifact，每次只比较一个固定分片。`CampaignParentCoverage.checkShard` 与 `CampaignObservedLinkComparison.compareShard` 复用现有原页、链摘要、成员及期间校验，局部分片的覆盖计数不声称是整个父集合覆盖。

每片至多 500 个对象，发布 `comparisonPage` 与 `selectionChain` 两个 LOCAL 输出。调用冻结范围、当前两个统计输入及上一链 Artifact 的实际元数据；链记录上一 Artifact 的 ID／载荷摘要、当前页摘要、累计分类数。无需在 Graph 或一次调用定义里保存所有页面元数据。最终 `selectedEntities`、`selectionEvidence` 是两个小 manifest，引用已发布链头；完整对象与证据通过耐久索引分页读取。

仅结构可比 `VERIFIED` 且 `delta<0` 的对象入选，保留全部匹配对象，按 `delta ASC, linkId ASC` 排序。不可比／未验证对象仍保留基期、目标期、差值、原因与原始证据引用。UNKNOWN 采集质量和对象存续、共同数据基础未证实等限制不升级为完整性保证，产物解释限定 `OBSERVED_ONLY`。

`selectionComplete` 仅表示固定候选均已获得两期证据并完成分类筛选，不等于全部对象可比，也不等于目标已回答。空集仅在覆盖完整且没有不可比／未验证对象时标记 `NO_DECLINES`；其余空集使用 `INSUFFICIENT_EVIDENCE`。

## 耐久与读取边界

`JdbcCampaignDeclineSelectionStore` 只登记实际 READY LOCAL 产物。每片载荷、链头和索引行一致后原子提交；重复登记须完全一致，跳片、改集合、换前驱不被接受。最终 manifest 已落盘也不提前开放分页，只有封存完成后才能读取。

读取验证当前主体与授权、期限、真实输入和全部页链，并核对索引行与原件一致。索引用于有序分页，不能替代产物真实性证明；游标绑定当前集合和最终产物，不能拿别的集合游标混读。只在 JVM 保留有界页面，不对全部对象做内存排序。

## 最小验证

3 项定向后端测试首次全部通过，失败、错误、跳过均为 0；日志 `.work/selection-pages-tests.log`。

| 选择器 | 关键断言 |
| --- | --- |
| `DeclineSelectionPublicationTest`（2 项） | 实际耐久 Scope501 与四份统计结果，经逐片 LOCAL／链头／最终 manifest／封存后，500 个下降对象全量分页，第501号最大下降排第一，501条证据全部保留；重建适配器后原 child attempt、hash、TTL 不变。验证 UNKNOWN 限制、跨产物游标、索引与源页篡改拒绝；完整无下降、不可比、缺口和空范围的不同语义；空范围零统计 Child，最终输出故障未封存不可读，撤权拒绝读取。 |
| `CampaignParentCoverageTest`（1 项） | 单片入口抽取后，原父范围完整覆盖／配对及损坏证据反例保持通过。 |

新增验收使用真实 JDBC／H2 账本与 `CampaignStepExecution.local`，未调用完整业务 Driver、真实进程接管或远端服务。已通过的其他测试未重复执行。

## 尚未交付

本批是已取得两期统计结果后的发布组件；正式 `decline_selection` Skill 的动态子查询准备、恢复／调度、版本注册及下游 `dimension_change` 仍须接入。它不自行调用模型或生成新 runner，也不打开生产路径。

读取仍采用全链完整性检查，保证内存有界但不是大范围分页性能验收。真实 MySQL 方言、锁与迁移、真实跨服务任务、模型及客户端均未运行；只按当前授权执行最小后端验证。
