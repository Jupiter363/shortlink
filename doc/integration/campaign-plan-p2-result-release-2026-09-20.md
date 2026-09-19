# P2 第二批：结果配额与恢复身份分账

关联 [Issue #62](https://github.com/Jupiter363/shortlink/issues/62)。本批实现远端结果释放协议，Agent 自动释放的消费登记与 releaseIntent 仍单独接线，新运行入口继续关闭。

## 行为

已完成的统计任务原先仍占每租户 8 个保留名额，单纯串行执行无法推进第 9 项。现在分别计算活动执行、未释放结果及其字节、恢复身份及其字节；计量使用数据库聚合，不把全部恢复记录加载到内存。

仅新冻结协议创建的任务允许 `release-result`。请求携带原 requestId 和完整原查询，由服务端校验当前主体、jobId、查询哈希、范围、epoch、有效期及终态。在 gate 和任务行锁下删除结果页及大 manifest 正文，保留原任务身份、冻结请求、manifestHash、终态与原到期时间。重复释放幂等；旧协议和活动任务不能释放。

提交命中原任务、recover-existing 和 status 均保留原 ID，并将查询终态与结果可读性分开。`resultState=RELEASED` 时，`resultReady=false`、`resultCode=RESULT_RELEASED`；页读取明确拒绝，不返回伪造空结果。Admin 三处结果状态映射及 Agent 专用网关遵循相同合同，不回退旧接口。

容量拒绝采用 `QUERY_CAPACITY_EXHAUSTED`，只对可信未受理回执返回 `admitted=false` 和 `capacityKind=ACTIVE_EXECUTION|RESULT_STORAGE|RECOVERY_IDENTITY`。网络失败、超时或 HTTP 429 不证明请求未受理，不能据此重新提交。

## 配置和迁移

统计控制库在 002 后应用一次 `deploy/mysql/005-analytics-result-release.sql`。旧任务回填结果状态，但默认禁止提前释放，原 TTL 不变。没有在当前环境执行迁移或启动服务。

容量配置前缀为 `analytics.jobs.capacity`。活动执行默认 tenant/global 为 2/8；保留结果默认 8/128，结果字节总量 1 GiB；恢复身份默认 2048/16384，身份字节预算分别为每租户 16 MiB、全局 128 MiB。各项可独立配置，保留身份不继续占结果名额。

## 验证范围与继续工作

42 项相关后端定向测试首次全部通过、无跳过：Admin 8、Agent 11、Analytics 23。包含第 9 项受理、释放后原身份／TTL、恢复零 INSERT、取消后旧 worker 无法复活、页读取与释放竞争、读取期间 epoch 改变及清理先赢 gate 的拒绝行为。

使用 H2 和短生命周期 HTTP fixture；不启动 Docker、应用或真实模型。MySQL IT 的初始化增加增量迁移，并检查字段避免重复执行 ALTER；只做编译检查，未运行 MySQL IT。真实 MySQL 并发和部署链路不在本次验收范围。

远端释放接口不会自动触发。后续必须先登记同一 physical job 的消费者关系，证明需要的页面、指标及 provenance 全部耐久且当前可读，再原子记录 releaseIntent；释放调用及丢 ACK 对账在事务外执行。当前 READY 子任务不能为了释放改回 DISPATCHING，也不能仅检查 metadata 就推断所有页可读。受控释放、可信未受理后的退避以及第 9 项的 Agent 自动续接完成前，不宣称 R18 或持续全量分析已经关闭。
