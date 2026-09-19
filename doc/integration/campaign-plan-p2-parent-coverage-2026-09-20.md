# P2：父范围统计覆盖与逐短链观测比较

接续 PR #79，关联 Issue #62 的 P2-02／03／04／08。完整成员 ScopeArtifact 已确定候选集合；本批验证两期间全部统计分片是否覆盖该集合，并把原始快照中的质量与期间信息带入逐对象比较。

## 真实数据合同

现有 `LinkMetrics.Accumulator.finish` 遍历全部授权成员，对没有事实的对象显式生成零行。单个冻结成员 shard 最多 500 个对象，因此 `LINK_METRICS` 应有同样数量且每个 ID 恰好一行。缺行、重复行、外来 ID 不是零访问，也不能通过再补零修复。

远端响应的 `completeness=COMPLETE` 描述查询结果完整；同时存在 `collectionQuality.status=UNKNOWN` 和 `missingMetrics=[producerCollectionCompleteness]`，因此不能据前者宣称遥测采集完整。没有对象当期存续信息，也不推断当时已经存在。

PV 为去重事件计数，互斥且完整的成员分片可以汇总父 PV。UV／UIP 使用近似去重，跨分片相加不构成父集合独立访客数；本批父汇总不提供这种相加值。

## 实现与验证

- `CampaignParentCoverage` 逐 shard、逐期间解析原 Child 请求、完整本地 receipt 和实际页，核对 ScopeArtifact 的成员、proof、期间、原请求摘要、独立查询汇总与 dense rows。缺失或不完整 slot 产生可流式记录的 gap，不形成伪造的完整覆盖；授权失败和输出 sink 异常中止。
- `CampaignLinkComparability` 用冻结期间及查询创建时刻判断已结束、等长、无重叠与指标定义；不因恢复时刻变晚升级旧查询。独立期间的 snapshot／manifest 可以不同，不凭不同判错，也不据此声称共同数据底座。保留每条短链的观测值、精确差值、基期非零时有依据的变化率及原因，基期零的 rate 为 null。
- `CampaignObservedLinkComparison` 把已验证的实际两期页按 ID 配对，保留每个对象的结果并流式交给调用方。最多保留一个分片的基期行，不积累全部成员；行序不同不会造成错配，没有基期时不补零。sink 的耐久事务与最终发布仍由后续 Skill 层负责。

定向选择器 `CampaignParentCoverageTest,CampaignLinkComparabilityTest`：3 项首次通过，0 失败、0 错误、0 跳过；日志 `.work/parent-coverage-tests.log`。没有重复运行前批已通过用例。

| 用例 | 关键证据 |
| --- | --- |
| CampaignParentCoverageTest（1 项） | 真实 H2 Run／Scope／Result stores，501 成员 × 两期间四个完整 slot，经实际页接线得到 501 条比较结果和 500 个负观测差；显式零保留，父汇总只含 PV。复用夹具验证漏行、重复、错父 proof、正文及自身 checksum 改写但独立发布链未改，以及授权／sink 异常原样传播。 |
| CampaignLinkComparabilityTest（2 项） | 独立期间不同 snapshot 可保留观测；UNKNOWN 采集与对象存续限制可见；零基期 rate=null，下降到零、大数与极小负变化率保持正确；指标/期间/冻结观察时刻/来源/质量冲突明确判定，不按当前时间升级，也不隐藏原始差值。 |

仅运行后端 H2／纯函数测试，未启动 Docker、应用、真实模型、MySQL 或前端。当前 shard 读取沿用 ScopeStore 每次完整枚举摘要校验，内存有界但大范围重复读取成本仍需在组合推进时优化；未用本批小型夹具冒充大规模性能验收。

## 后续交付边界

覆盖齐全、观测差计算完成与 `selectionComplete` 分开。完整下降筛选的确切入选集合及证据仍需要作为版本化 Skill 输出耐久发布，随后才能绑定动态集合维度分析。本批不注册生产入口，不提前打开探索或客户端新结果路径。
