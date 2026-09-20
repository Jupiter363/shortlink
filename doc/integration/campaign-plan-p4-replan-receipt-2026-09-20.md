# P4 重规划耐久收据 · 2026-09-20

接续 E48，为证据门控的 `ReplanRequest` 增加内部 JDBC 收据边界。`JdbcReplanReceiptStore` 以 `(run_id, base_revision)` 唯一记录一次候选决策，写入前锁定并核对当前 ACTIVE Run 的 caller、revision、row version 和 advance token。相同请求的重放返回原收据；同一 revision 的候选 hash、正文、decision 或 reason 不一致时返回 `REPLAN_RECEIPT_CONFLICT`，不会覆盖第一次事实。

收据保存候选 revision、候选 Plan hash、受界定的请求 JSON 和稳定 reason code，但不执行 `RunStore.revise`、不编译 Graph、也不接管 consumer。这样可以先耐久保存 REQUEST_REPLAN 事实，后续在同一 REQUIRED 事务中完成候选验证、Graph 预编译和本地 consumer adoption，再 CAS 发布新 revision；当前实现故意保持生产入口关闭。

## 最小验证

`JdbcReplanReceiptStoreTest#recordsAndReadsAnIdempotentReceipt` 使用 H2 验证：有效 token 可写入并读取；相同请求幂等且只保留一行；同 revision 的不同候选被拒绝。定向 Maven 运行 1 个方法，0 失败/错误/跳过。没有启动 Docker、应用服务、真实 MySQL、模型或 Graph。

## 边界

迁移 `V20260920_21__campaign_replan_receipt.sql` 只新增收据表；尚未把收据接到 `PersistentPlanDriver` 的 REQUEST_REPLAN 分支，也没有实现新 Graph/consumer 的事务内切版、跨 Run 复用或生产路由。H2 结果不能替代真实 MySQL 锁和迁移验收。
