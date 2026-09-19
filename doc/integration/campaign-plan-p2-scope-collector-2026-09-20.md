# P2：耐久范围收集与固定分片

对应 Issue #62 的 P2-01／02、V29、R14。本批接通严格权威成员页和本地 ScopeArtifact；不注册生产入口，不把这一组件交付当作整个 P2 已完成。

## 实现边界

- `CampaignScopeCollector` 每次只推进明确数量的页；这是可续接的工作批次，不是总成员数上限。页请求及 SYNC Child 在实际 I/O 前提交，禁止在外层事务内调用 collector。真实回调退出才释放许可。
- Collection 固定 run／revision／action／gid／保留期限。已收集成员正文按页只保存一次；非终页 Child 发布小型 PageRef。最后一页的真实权威 Child 在同一 REQUIRED 数据源事务内保存终页、验证完整枚举、发布 ScopeArtifact 并标记 PUBLISHED，不伪造本地 HTTP 调用。
- 首屏未知响应保留 `READ_RESULT_UNKNOWN`，不重发未固定版本的读取。后续未知页仅通过新的 `AUTHORITY_PAGE_READ` 许可重读原路径、原游标和原 ownershipVersion；不能用于普通 SYNC、异步提交或 READY 页。
- 成员版本变化使整个 collection 进入 INVALID，不将不同代次的成员拼成全集，不自动切到新版本。取消、旧 writer、当前授权失效均阻止结果发布。
- `FrozenCampaignScope.summarize` 逐页校验严格递增成员、连续游标、唯一版本和终页，增量计算原有规范 memberHash／scopeRef。内存不保留整个成员集合或全部 shard。
- 最终清单保存集合引用、成员摘要、枚举版本、成员／页／分片数。读取 shard 时校验实际 Artifact、当前主体和授权、到期时间与耐久页摘要；每个 shard 最多 500 个 ID。两期间复用同一 ScopeArtifact，授权空组产生零 shard。

`ownershipVersion` 是完整枚举的一致性证据，不是可回读的历史快照。冻结后新增成员不会自动加入；每次实际统计查询仍须经过已有指定成员授权。后续组合层还需要将所有 shard 的统计结果做覆盖及可比性对账，不能将成员齐全误写为统计结果齐全。

## 验证

定向选择器：`PinnedAuthorityPageDispatchTest,FrozenCampaignScopeSummaryTest,CampaignScopeCollectorTest`，最终 6 项唯一测试全部通过、无跳过。首次测试编译发现夹具使用了高于仓库 Java 17 的 List API，修正后执行；5 项通过，1 项遇到 Mockito final 类代理限制，改为接口委托后只重跑该用例并通过，未重复已通过测试。日志：`.work/scope-collector-tests.log`、`.work/scope-collector-recheck.log`。

| 用例 | 关键断言 |
| --- | --- |
| PinnedAuthorityPageDispatchTest（2 项） | 固定原请求的未知 SYNC 页可重读；READY、首屏、任意路径、坏字段、旧 attempt、取消及未真实退出回调均受围栏约束。 |
| FrozenCampaignScopeSummaryTest（1 项） | 5501 成员按 12 页流式计算；与旧规范 scopeRef／hash 相同；不完整前缀、变版本拒绝，空组零 shard。 |
| CampaignScopeCollectorTest（3 项） | 501 成员中断后不重读第一页；两期获得相同 500＋1 shard；首屏未知与固定页未知区别；变版本整代失效；终页发布失败原子回滚后恢复；撤权／取消不发布；空组不创建统计 job。 |

只使用 H2 与脚本权威页，不启动 Docker、应用、真实模型或数据库服务。迁移 `V20260920_4` 扩展 attempt purpose 字段，`V20260920_5` 增加范围及成员页表；尚未在实际 MySQL 上应用或验证并发锁行为。

## 下一步

将范围凭证接入注册版本的复合 Skill／Plan 绑定，完成两期间全部分片的结果覆盖、逐对象可比性与下降筛选。生产可信授权装配、P3 持久探索、P4 消费接管和 P5 报告／客户端仍按总矩阵继续推进。
