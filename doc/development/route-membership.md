# 跳转布隆过滤器的部署与恢复

布隆过滤器位于 Redirect 的 L1 miss 与 Redis L2 之间。新地址在 Command 提交路由之前登记；Redirect 追齐持久登记后获得短期否定租约。Kafka `shortlink.route.membership.v1` 只加速唤醒，漏消息或消息积压不授予错误的否定权限。

## 初始化与启用

1. 对明确指定的业务库执行 `deploy/mysql/004-route-membership.sql`，先于新版 Command / Redirect 构造数据源。该迁移只增加两张表，重复执行不重置已有登记或模式。新环境在 `001-business-schema.sql` 后执行；旧库必须先完成备份与结构核对。
2. Command 账号需要登记表的 `SELECT/INSERT`、控制表的 `SELECT/UPDATE` 和原有 Outbox 权限；Redirect 账号只需要两张新表的 `SELECT`。这些表使用同一业务主库，禁止将登记与业务发布拆到不同库或把租约读取指向副本。
3. 创建 Topic `shortlink.route.membership.v1`，与现有 Kafka 配置统一 RF/minISR。Command 对其拥有 Write/Describe；Redirect 的独立 `redirect-membership-<instanceId>` consumer group 拥有该 Topic 的 Read/Describe 和组权限。消息不包含原始 URL、账号、访客信息。
4. 部署所有写入口的新代码，包括单条、同步批量、异步批量与恢复入口。Command 始终登记，即使过滤器当前 OFF。为参与过滤的 Redirect 配置 `ROUTE_MEMBERSHIP_ENABLED=true`，先启动同步；本地开关只允许检查，不能越过数据库 OFF/SHADOW 门禁。不能在旧写进程仍可提交路由时开启 ENFORCE。
5. 操作工具执行 `init`。工具先进入 DRAINING，暂停登记/业务发布、排空旧否定租约，再按最多 500 个地址分页导入所有路由状态；核对没有未登记路由后进入 SHADOW。失败保留 DRAINING，排查后重新执行 `init`，不手工修改 mode。
6. SHADOW 下观察容量、同步进度和 UNKNOWN 原因，完成创建/跳转验收后，显式执行 `enforce`。它再次排空并验证覆盖后才开启否定租约。节点自身仍须追齐登记并领到有效租约，才会挡住不存在地址。

第一次迁移的状态是 **OFF**，不是自动启用。原有压测/E2E 初始化器只创建新表，默认也保持 OFF；其旧性能结果不能标成 Bloom 已启用的成绩。运行启用验收时必须保存操作工具状态和节点指标。

| Redirect 环境变量 | 默认值与含义 |
| --- | --- |
| `ROUTE_MEMBERSHIP_ENABLED` | `false`；设为 `true` 后启动本地同步与检查，是否允许否定还由主库模式和租约决定 |
| `ROUTE_MEMBERSHIP_CAPACITY` | `1000000` 个地址，超出时放弃否定权限并告警 |
| `ROUTE_MEMBERSHIP_MEMORY_BYTES` | `33554432`；预算计入多个过滤器与快照缓冲并存 |
| `ROUTE_MEMBERSHIP_KAFKA_HINTS` | `true`；关闭提示加速后仍依靠主库轮询恢复 |
| `ROUTE_MEMBERSHIP_SNAPSHOT_PATH` | 空，默认从主库分页建立视图；可选实例独占的本地快照文件，不保存租约 |

## 显式操作工具

工具入口为 `com.jupiter.shortlink.membership.MembershipAdminMain`，构建和操作均在仓库根目录执行。示例为 PowerShell：

```powershell
# 使用 Java 17。先构建并安装工具与其公共库依赖。
mvn -B -ntp -pl :route-membership -am install -DskipTests
mvn -B -ntp -f libraries/route-membership/pom.xml org.apache.maven.plugins:maven-dependency-plugin:3.6.1:build-classpath -Dmdep.outputFile=target/runtime-classpath.txt -DincludeScope=runtime
$membershipCp = (Resolve-Path 'libraries/route-membership/target/classes').Path + ';' + (Get-Content 'libraries/route-membership/target/runtime-classpath.txt' -Raw).Trim()
# 从受保护的会话/配置提供 MEMBERSHIP_DB_URL、MEMBERSHIP_DB_USER、MEMBERSHIP_DB_PASSWORD。
# 不将密码写进命令参数、文档或 Git。
java -cp $membershipCp com.jupiter.shortlink.membership.MembershipAdminMain status --confirm-catalog <实际业务库名>
java -cp $membershipCp com.jupiter.shortlink.membership.MembershipAdminMain init --confirm-catalog <实际业务库名> --confirm-writers-upgraded
java -cp $membershipCp com.jupiter.shortlink.membership.MembershipAdminMain enforce --confirm-catalog <实际业务库名> --confirm-writers-upgraded
```

`--confirm-catalog` 必须与实际连接的 catalog 相等。`--confirm-writers-upgraded` 表示操作者已确认所有新地址入口执行登记协议；工具无法通过扫描表证明另一个旧版本进程永远不会写入。这是维护操作的明确前提，不由服务启动或定时任务代替确认。

`status` 只读。`shadow`、`off` 是有界排空后的模式切换；操作期间会暂时拒绝新建/发布，但不强制停止已有正常跳转。工具只输出登记版本、数量、generation、模式等状态，不输出数据库凭据。这里的构建跳过测试仅用于已验收版本的运维准备，不替代 PR 测试验收。

## 正确性与资源边界

- 阴性必须绑定同一份 filter、applied cut 和仍有效的租约。临响应提交重新检查；不把 Bloom 阴性写入普通 L1/L2 NOT_FOUND 缓存。
- 租约最大 1,000 ms；写入在登记提交确认后，以单调时钟等待 1,250 ms（含 250 ms 安全余量）再发布。单批等待一次，异步调度有并发和队列上限。创建延迟约增加 1.25 秒加调度/数据库时间，需纳入管理调用超时和批量吞吐评估。
- Command 发布默认总预算 4,000 ms，MVC 异步响应预算 5 秒，与 Admin 的 5 秒读取超时配合。`ROUTE_PUBLICATION_DEADLINE_MS` 调整时同步核对外层超时；超时后不再启动新发布，已发出的数据库提交可能存在结果未知，使用原创建 requestId / 批量持久 ID 查询或幂等重试。
- UNKNOWN、数据库同步失败或租约过期回退现有 L2 / 限额回源。Bloom 阳性不能绕过停用、到期、归属和风险策略。
- 登记只增不删，包含业务回滚后的候选及仍可重试的 RESERVED 地址。长期增长按地址数配置内存上限；不要用 Leaf 高水位替代真实集合。
- 已明确耗尽配额的同步创建在领取 Leaf ID 前拒绝；并发竞争或事务回滚留下的登记仍保留，只增加假阳性，不清除位。已成功请求的幂等重放不受后续配额耗尽影响。
- 该版本从主库登记分页恢复进程内视图，不信任持久化的旧进程租约。主库和登记不可独立回滚；恢复主库前必须隔离旧写进程，停止发租并排空旧权限，恢复后重建/核验基线。
- Kafka hints 与路由失效不是一套进度。即使跳过旧 hint，只要主库分页未完整追齐就不能发租；Kafka 消费成功不表示 Bloom 完整。
- 每请求不增加远程 Bloom 调用；后台同步和发租有数据库成本。APISIX 限流、负缓存、singleflight、集群回源预算继续兜底。

`deploy/monitoring/alerts.yml` 增加容量不足及持续无法授予否定权限的告警，基于实际 UNKNOWN 请求触发，默认关闭和 SHADOW 不会触发这两项告警。同时观察 `shortlink_redirect_membership_applied_lag`、`shortlink_redirect_membership_lease_remaining_millis`、`shortlink_redirect_membership_filter_expected_fpp`，区分容量、主库故障和追赶中状态。

## 停用与回滚

先执行 `off` 并等待工具成功完成排空，再回滚到不登记的旧 Command。不能只改 Redirect 的本地开关、删除 Redis key 或直接 `UPDATE mode='OFF'`，随后就允许旧创建代码写入。

已发出的业务事务、待提交的注册 permit 和异步批量任务均受到 control generation/mode 检查。维护操作失败时保留证据，重新运行受控命令；不要删登记位图或登记行来“修复”数量。恢复 ENFORCE 必须重新验证基线和写入入口。

设计和风险说明见[实施计划](../plan/生产级重构增强/27-跳转布隆过滤器与缓存穿透防护计划.md)。该能力不改变 Agent 的数据采集及统计 Tool，拒绝访问不计作成功 CLICK。
