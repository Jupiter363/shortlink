# P2：真实联合维度证据与分页比较

日期：2026-09-20。关联 Issue #62，承接 E21 的派生范围。此批验证和比较已完成的两期 `province × device` 结果；实际 `dimension_change` Skill 的动态提交和 NativeGraph 接线继续实施。

## 来源与统计口径

`CampaignDimensionEvidence` 从真实 READY child、原冻结请求、统计接收 receipt 和发布 manifest 复核来源。它逐页验证 Protocol context、scopeProof、原日期／过滤、页链／hash、全窗 summary、联合桶的 state/value、PV 分母和总和以及跨页唯一性。零行仍读取实际 page0，不凭 pageCount=0 跳过验证。

UNKNOWN 与 province 的 NOT_APPLICABLE 分开；KNOWN 保留原字符串。PV 比例遵循现有服务的 double 除法合同；UV/UIP 保留各次查询独立去重结果和近似属性，不跨桶或分片求和，也不将近似值强制裁到 PV。查询 COMPLETE 不提升 collectionQuality.UNKNOWN。

不缓存全部联合桶。来源证明只保留至多 10 个页 hash；验证时最多读取两页和当前页 500 个键。已验证对象只能由验证器构造，后续读取仍校验当前来源、授权、期限与原页 hash。

## 比较与发布

`DimensionChangePublisher` 消费真实 SelectedScopeArtifact 及两期分片结果，在原日期和明确成员范围内逐页对齐联合键。先发布上期各页，再发布本期中上期不存在的桶，保留完整并集；每页至多 500 行，不作 Top N 截断。只有完整查询证明下不存在的桶才补零；查询缺失不能补零。

结果注明 `SHARD_COHORT` 和 `OBSERVED_ONLY`。每页交错保留桶数据、PV 差值／份额变化、独立整窗 summary、原质量与限制。两期口径复用已有查询评估规则，无须伪造 linkId；不可比或未验证时不生成增长率。各页链由真实 LOCAL 发布，小 final manifest 引用头页及覆盖计数，不存放全部桶。LOCAL 计算在耐久准备后执行，READY 复用不重新计算桶差。

相同分片全部源页完成后才进入下一分片，最终头页必须结束于最后分片的最后源页。页链、来源产物、当前授权、原期限和同一生产步骤均须匹配。空入选集合不发查询，最终分别表达 NOT_APPLICABLE 或 INSUFFICIENT_EVIDENCE。

## 最小验证与边界

4 项最小相关测试最终全部通过。首轮运行 `CampaignDimensionEvidenceTest`（2 项）及受影响的 `CampaignLinkComparabilityTest`（2 项），比较规则两项通过；维度两项定位到 Publisher 浮点 JSON 节点与 LOCAL 注册表 BigDecimal 节点的等价校验不一致。Publisher 统一解析为 BigDecimal 后，仅重跑受影响的两项维度测试，失败、错误、跳过均为 0。日志 `.work/dimension-evidence-tests.log`、`.work/dimension-evidence-retest.log`。

真实 H2 测试覆盖两期各 501 桶、完整 502 桶并集、四个 LOCAL 页及小 manifest、独立整窗 UV=2 而桶 UV 合计501、UNKNOWN/NOT_APPLICABLE、原期限与 READY 重用；缺少 READY、错期／范围、重复联合键、无效 state、分母及总 PV 不一致均拒绝。零流量期间依然读取 page0 并完成三页比较；指标版本变化发布 INCOMPATIBLE 观测结果并抑制增长率。外部响应为脚本数据。

没有启动 Docker、应用、模型或前端。跨服务真实环境和大集合性能未验；完整来源校验与跨页配对采用有界内存扫描，不能以此声明吞吐已达标。当前合同固定 province/device，其他联合维度组合与正式用户入口未由本批交付。
