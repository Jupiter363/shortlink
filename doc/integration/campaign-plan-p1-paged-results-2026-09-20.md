# P1 第七批：分页耐久接收与原生 Graph 续接

跟踪 [Issue #62](https://github.com/Jupiter363/shortlink/issues/62)，承接 [PR #70](https://github.com/Jupiter363/shortlink/pull/70) 的结构化任务读取合同。组件仍为未注册的后端运行能力，不新增模型工具、HTTP 路由或调度循环。

## 行为

`StatisticsJobResultReceiver` 只处理账本中已知的原异步统计 job。每次推进读取一次状态，然后沿持久化游标接收一批页；原 requestId、冻结正文、jobId 和输出绑定保持不变。默认每次接收 8 页，可配置，后续推进继续下一页，没有继承旧工具 10 页／5000 行的收集上限。真正服务端的数据范围上限仍按原 Analytics 协议执行。

`StatisticsJobResultProtocol` 在接收前核验 job、查询类型、分组或短链、期间、快照、epoch、版本、成员、维度／筛选条件和页边界。维度筛选复用现有 `DimensionQuery`，没有再写一套查询解释器。每页行数必须符合固定 500 条物化分页；页码连续，末页的续页引用必须为 null，总行数与冻结状态一致。

零行任务也必须接收 page 0：虽然远端 pageCount 为 0，该页仍携带整窗汇总、范围与质量。`byteCount` 包括源端独立汇总载荷，不能与 Admin 富化后的响应 JSON 大小作相等比较。

## 保存与发布

新增 `campaign_statistics_receipt` 和 `campaign_statistics_page` 两张表。每页的正文、校验和、行数和下一页位置在同一事务中提交，并核对当前 Run token 和真实回调 attempt。同页同校验和的重放幂等，冲突页拒绝；已经接收的内容不被覆盖。

页间快照和整窗汇总必须一致。JSON 只排序对象键，不重排数组，不截断分析数据，也不累计相加 UV／UIP。当前采用保守的完整响应校验：如果 Admin 富化的名称或 URL 等信息在两页之间改变，保留旧页并明确阻断，不混合不同观察。质量为 `RETURNED_PAGE_LEGACY_JOB` 的旧式结果不冒充整窗质量。

全部页对账完成后，在同一事务内发布 `StatisticsJobPages / statistics-job-pages/v1` Artifact、把 child 标记 READY，并将接收记录标为已发布。Artifact 的小型 manifest 保存页数、总行数、链式校验和、原快照和汇总；完整页正文单独耐久保存，既不拼成一个大对象，也不塞进 Graph checkpoint。

只有已发布的页能通过 `readPage` 读取。每次单页读取前后检查当前主体、授权和有效期，并验证正文校验和；STAGING 内容不能作为可用结果读取。单页默认保护上限 8 MiB，可配置；没有另设总页数上限。远端 HTTP 有界传输仍保留原保护。

## 中断与授权

- 已保存第一页后重新构建接收器，从下一页继续；不会重新提交或重取已经耐久保存的页。
- 已收齐但发布前中断，下一次复核原任务状态后直接完成发布，不重读全部页面。
- 未完成的原任务仍为 WAITING；证据过期、查询失败、协议不符或范围改变保留明确失败及已保存片段，不替换 job／snapshot 拼接。
- 每次实际 status/page I/O 前、响应后及发布前重新检查推进资格和授权。取消或撤权后不能继续取页或发布成功；回调只能在真实 finally 退出时释放。
- `CampaignRecoveryCoordinator.Runtime` 由可信执行器显式提供稳定的结果绑定。只有配置了接收器及绑定的 child 才接收；没有绑定的既有恢复方式不变。接收完成后复用真实 StepStore 的唤醒检查与原生 PlanGraph，未齐全的页不唤醒依赖步骤。

## 验证与启用边界

仅运行本批新增和直接受影响的后端定向用例，使用真实 H2、JDBC Store、PersistentPlanDriver 和原生 MemorySaver，统计响应由网关替身提供。没有启动 Docker、应用服务或真实模型，也未连接现有业务数据库。

**18 项定向后端测试首次全部通过，无失败、无跳过**：存储 `JdbcCampaignStatisticsResultStoreTest` 5 项，协议 `StatisticsJobResultProtocolTest` 4 项，接收器 `StatisticsJobResultReceiverTest` 5 项，实际 Graph 接收续接 `CampaignPagedResultGraphTest` 1 项，以及本次直接修改的 `CampaignRecoveryCoordinatorTest` 3 项。覆盖 501 行两页断点恢复、5501 行超过旧 10 页限制、撤权／取消、整页齐全后发布前异常、两个数据库写入故障点的事务回滚，以及分页未齐时依赖不执行、发布后实际推进。

只执行上述选择器一次，没有重复此前已通过的其它后端用例，也没有跑全模块回归。

`resultComplete=true` 只说明原任务页已全部接收。UNKNOWN／PARTIAL 质量仍原样保留，不等于目标 ANSWERED。结果证明范围仅为 `CURRENT_QUERY`，没有生成 P2 尚未实现的 FROZEN_SET／scopeProof。生产业务执行器装配、跨期固定全集、结果释放和客户端兼容仍按后续阶段实施；P1 保持实施中，新运行入口关闭。
