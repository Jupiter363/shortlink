# P1 第八批：结果接收进度

跟踪 [Issue #62](https://github.com/Jupiter363/shortlink/issues/62)，承接 [PR #71](https://github.com/Jupiter363/shortlink/pull/71) 的分页耐久接收。此批扩展后端授权只读视图，仍不注册生产组件、HTTP 路由或调度器。

## 对外语义

`campaign-progress/v1` 的步骤新增可选接收列表 `resultReception`，每项只表示一个已登记结果的 `receivedPages / totalPages / receivedRows / totalRows`。复合步骤可能还有未提交或尚未产生接收记录的查询，因此不提供误导性的整个步骤总量或百分比；空列表表示没有可报告的接收记录，不表示已取得零行结果。

零行任务仍需读取携带汇总和质量的 page 0，因此接收页数为 0/1 或 1/1。页已收齐也不直接改变步骤状态，不生成可用 Artifact 引用，更不改变 `DeliveryState.NOT_ASSESSED`。只有原发布与步骤验收路径能推进执行状态，后续目标验收另行决定是否完成回答。

旧的三参数 `CampaignProgressService` 构造方式保留，默认没有接收列表。启用新投影时必须同时提供一致快照读取器、当前范围授权器和时钟；尚无生产装配。

## 授权与数据边界

暂存结果还没有 Artifact，使用内部冻结 Run、范围和期间引用检查当前访问权，不伪造 Artifact 元数据。撤权返回 `RESULT_ACCESS_DENIED`，过期返回 `RESULT_EXPIRED`；这些项的全部计数为 null，不补零，不暴露内部 jobId、requestId、Artifact ID、范围引用或校验和。

已有可用输出仍走原 Artifact 授权逻辑。Run 的身份校验、取消状态和交付未验收语义不变。

## 一致快照

`JdbcCampaignResultProgressReader` 在同一个 REQUIRED 数据库事务中复用 `JdbcCampaignStepStore.snapshot`，锁定当前 Run 与步骤，再读取该 revision 的接收计数。分页追加、取消和步骤提交遵守同一个 Run 锁顺序；本读取不获取新 writer token，不修改账本。

查询只取步骤归属、不可变接收规格及计数，核验规格校验和和计数边界。不读取整页正文、快照、汇总或冻结请求正文，也不先读步骤再另行逐个调用 `receipt()` 拼接不同时间点的状态。接收记录必须关联已登记步骤，损坏或不匹配的记录明确拒绝。

## 验证与后续

**10 项最小必要后端测试首次全部通过，无失败、无跳过**：新增 `JdbcCampaignResultProgressReaderTest` 3 项、`CampaignResultReceptionProgressTest` 3 项，加上本次直接受影响的 `CampaignProgressServiceTest` 4 项。

覆盖实际暂存 500/501 行、零行汇总页、未登记查询、损坏记录、错配主体、取消后只读、旧新 revision 隔离、查询不加载大列，以及撤权／到期／授权期间到期隐藏计数。原步骤进度、可用输出和交付未验收语义保持兼容。只执行上述选择器一次，未重复前批已通过测试。

使用 H2 与脚本化边界，没有启动 Docker、应用服务或真实模型，也未连接现有业务数据库；SQL 的实际 MySQL 方言及真实服务链路仍未验收。

本批不构成前端进度条上线，也不代替生产业务执行器装配、固定范围、结果释放和客户端状态兼容。P1 仍实施中，新运行入口继续关闭。
