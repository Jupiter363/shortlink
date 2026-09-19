# P2 第三批：本地结果证明与释放恢复

关联 [Issue #62](https://github.com/Jupiter363/shortlink/issues/62)。本批接通可选的 Agent 结果释放流程；新运行入口继续关闭。

## 行为与边界

只有新冻结协议的 ASYNC READY 子任务可以进入释放流程。先读取并验证本地 manifest、每一页的内容与校验和、页链、原查询、scopeProof、生产者、当前授权和原有效期，再在事务中登记 REQUESTED。按页校验保持常量页面内存；零行任务也必须有 page 0 的证据。

物理绑定按 tenant、subject、jobId 唯一，并固定一个 producer、原 requestId/hash、Artifact/hash、页链和期限。首期只支持单生产者，消费方从本地 Artifact 读取。检测到另一个远端生产者时拒绝释放；跨 Run adopt、多远端消费者和页面 GC 尚未实现，后续必须在同一绑定上协调。外键保护本地 Artifact、manifest 正文和接收记录，不能据此宣称全部未来清理／消费竞争已完成。

释放使用独立 RELEASE attempt，只登记回调资格，保留 READY、Artifact 以及步骤具名输出。它不能调用修改结果的 recordWaiting、publishReady、recordLateJob 或 markUnresolved。每次网络调用前同时核对当前 Run、当前授权、精确 attempt 和释放绑定；网络调用在事务外执行，实际回调退出后才清 callback。

协调器在本地完整发布后可执行释放，再继续原生 Graph。释放失败不丢弃本地结果或阻断已具备证据的下游。每次未确认流程先读取原 job 状态：AVAILABLE 才调用幂等 release-result，RELEASED 则只确认原意图；不读取远端页，也不重新提交。已经 CONFIRMED 的再次推进不产生 HTTP。

取消或换版阻止新 I/O。已经在途的精确旧 attempt 可以记录晚到的释放确认，不恢复 Run、不改变 READY；已经被替换的 attempt 不可确认。原 jobId、requestId、scope、Artifact 与 TTL 均不改变。

## 迁移与验证

新增 `V20260920_2__campaign_statistics_release.sql`，在现有 Run、Step、Owner 和 Statistics Result 迁移之后应用。当前未对运行环境执行迁移。

12 项定向后端测试首次全部通过，无失败或跳过：RELEASE permit 2 项、本地释放 Store 3 项、Releaser 3 项、受影响的 Coordinator 3 项、固定查询→发布→释放→下游读本地证据的集成 1 项。包括损坏末页、STAGING、撤权、原期限、重复确认、回执丢失后重开、旧 attempt、取消／换版及实际 callback 退出。日志：`.work/release-coordination-tests.log`。

仅使用 H2、原生 Graph 和远端替身；不启动 Docker、应用或真实模型。真实 MySQL 与生产部署仍未验收。

本批不关闭 R18：可信未受理回执的持久退避、只推进剩余子项及 Agent 第九项自动续接仍需继续接入；P4 多消费者采用和 P5 留存／清理也仍在总计划内。
