# P2 第四批：可信未受理退避与剩余查询续接

关联 [Issue #62](https://github.com/Jupiter363/shortlink/issues/62)。新运行入口继续关闭，本批完善固定统计执行链路。

## 问题与修复

服务端明确返回 `QUERY_CAPACITY_EXHAUSTED / admitted=false` 时，固定执行器原先仍将其当作提交结果未知。远端没有创建任务，而本地只能通过 recover-existing 查找任务，因此容量恢复后也无法继续。

现在只有失败响应中的精确业务码、布尔 `admitted=false`、合法容量类型及封闭字段集合才构成未受理证明。缺字段、字符串 false、错误容量类型、额外 jobId、网络异常或丢回执都不允许回到待提交状态。

可信拒绝在精确 FRESH ASYNC attempt 下原子登记持久证明，将没有 job/Artifact 的 child 回到 PREPARED。原 action、child、requestId、wire/hash 保持不变。重复处理同 attempt 的同一拒绝不累计次数或推迟期限，实际 callback 仍到真实退出时才释放。

退避由服务端 Clock 计算，默认首次 1 秒、指数增加到 30 秒；可通过可信装配的 SubmissionBackoff 调整。每个拒绝保存容量类型、拒绝次数、最早重试时间和精确 attempt。beginDispatch 在数据库锁内检查期限，重开对象或反复推进不能提前提交。到期仅代表允许再尝试，不证明远端已经有容量。

恢复协调继续先接收／释放已有任务，再刷新到期步骤并执行原生 Graph。只有包含耐久拒绝证明、无活动 callback、没有未知或尚未完成的远端子任务的步骤，才允许再次就绪。child 拒绝已落盘但父步骤尚未记录状态的崩溃窗口，同样凭耐久证明恢复；不能只凭 STEP_RESULT_UNKNOWN 自动重试。

已保存的 READY 结果继续复用；未到期的拒绝不做 submit 或 recover-existing，不新增轮询器或 sleep。混合步骤仍保留已有 pending，待它们 READY 后再推进未提交项。

## 迁移与验证

新增 `V20260920_3__campaign_submission_deferral.sql`。旧任务没有可信拒绝证明的，不自动迁回 PREPARED；容量拒绝在落盘前丢失时仍按未知提交处理。

7 项定向后端测试首次全部通过，无跳过：持久退避与混合步骤 3 项，九任务端到端 1 项，严格拒绝回执与受影响的提交恢复／释放读取 3 项。九任务用例使用真实 H2、固定执行器和原生 Graph：成功 job 为 9 个，提交共 10 次（第九项初次拒绝后重试），接收与释放各 9 次，recover-existing 为 0；999ms 前反复重建／推进零远端调用，1000ms 时仅重试原请求。前八项的 Artifact、页面、范围和原期限保持不变。日志：`.work/capacity-continuation-tests.log`。

使用 H2、可控时钟和远端替身，不启动 Docker、应用、真实模型或 MySQL 测试；不对当前运行环境执行迁移。远端分账本身由前批 E12 验证，本批不冒充真实跨服务部署验收。

该实现不替代生产授权与客户端装配、P4 多消费者采用竞争或 P5 历史留存。R18 的服务端分账、单生产者释放和本地退避分别记录证据，不能将本批单独视为总计划完成。
