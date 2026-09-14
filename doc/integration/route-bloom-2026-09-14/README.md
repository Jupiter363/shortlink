# 跳转布隆过滤器验收记录

日期：2026-09-14。基准：main `c15e10d`。开发分支：`jupiter/feat-route-bloom`。需求：[Issue #15](https://github.com/Jupiter363/shortlink/issues/15)。

本次为单元与隔离组件集成验收，未运行压测、完整 APISIX E2E 或 Agent UAT。未改变原本停止的本地应用状态，也没有在既有业务库自动启用过滤器。

## 实现结果

- Redirect 在 L1 miss 后、singleflight / Redis L2 前检查本地 Guava Bloom。可靠阴性直接 404，不查询 L2 或业务路由、不产生 CLICK、不写普通负缓存；提交响应前重验证明，失效则在原请求预算内回退一次。
- 单条、同步批量及异步 RESERVED 重试均先持久登记地址及 Outbox 通知，再按批次异步等待 1,250 ms，最终业务事务验证登记与维护代次。Leaf 的号段和编码不变，不恢复 Hash 碰撞循环。
- 读侧只有完整追齐当前登记版本才能领取最长 1,000 ms 的否定租约。Kafka 只唤醒同步，漏通知不会成为错误否定的依据。容量不足、快照损坏、租约过期或数据库异常均回退原查询。
- 新建配额已明确耗尽时，在领取 Leaf ID 前拒绝，避免无效请求持续增长登记。业务事务仍执行最终配额校验，已完成请求仍能幂等重放。

## 测试结果

来自本次 Surefire / Failsafe XML 的去重统计，重复构建的公共库只计一次，详见 [acceptance.json](acceptance.json)。

| 范围 | 通过 | 跳过 | 失败 / 错误 |
| --- | ---: | ---: | ---: |
| event-contract 单元 | 258 | 0 | 0 |
| id-generator 单元 | 19 | 0 | 0 |
| risk-core 单元 | 101 | 0 | 0 |
| route-membership 单元 | 23 | 0 | 0 |
| Command 单元 | 104 | 0 | 0 |
| Redirect 单元 | 174 | 2 | 0 |
| 协议真实 MySQL 集成 | 8 | 0 | 0 |
| Command / Batch 真实 MySQL、ShardingSphere 集成 | 42 | 0 | 0 |
| Redirect 真实 MySQL / Redis 集成 | 3 | 0 | 0 |
| Kafka 提示消费集成 | 1 | 0 | 0 |
| Python 初始化 / 路径回归 | 12 | 0 | 0 |

Java 单元合计 679 通过、2 跳过；Java 集成 54 通过。两项跳过均为既有 Linux 文件描述符测试，当前 Windows 11 平台不执行，不计为通过。命名、逐套件计数和集成用例见 JSON；其中登记写入与业务回滚、取消后回滚、RESERVED 原 ID 重试、只读共享锁及临响应租约过期均有回归。

Kafka 用例通过真实 broker 分区分配和唯一测试消息确认唤醒，LocalRouteMembership 以 mock 记录 hint 调用；它验证通知传输契约，不冒充完整创建→Outbox publisher→Kafka→Bloom 的跨进程 E2E。MySQL / Redis 用例使用真实登记、路由权威及缓存，控制单调时钟以稳定覆盖过期边界。

操作工具的 `status → init → enforce → status → off → status` 全部成功，最后回到 OFF。结果见 [maintenance-cli.json](maintenance-cli.json)。Prometheus 官方 `promtool v3.2.1 check config` 验证配置与全部 6 条规则通过，包含新增容量和否定权限不可用告警。

## 环境及复现

Java 17；Maven 3.9.4；Maven 堆 384 MiB、测试 JVM 堆 512 MiB。隔离 Docker Compose 项目为 `shortlink-bloom15-it`，无压测程序：

| 依赖 | 镜像 | CPU 上限 | 内存上限 | 本机端口 |
| --- | --- | ---: | ---: | ---: |
| MySQL | `mysql:8.0.36` | 1 | 512 MiB | 23306 |
| Redis | `redis:7.2.5-alpine` | 1 | 128 MiB | 26379 |
| Kafka | `apache/kafka:3.9.0` | 1 | 640 MiB | 29092 |

Kafka 为单 broker、测试 Topic 单分区，RF/minISR 为 1，只用于功能验证。测试库为 `shortlink_bloom15_it`、`shortlink_business_it`、`shortlink_batch_it`、`shortlink_bloom_redirect_it`；Redis 使用 DB 6 的随机 generation key，仅清理本用例 key。密码通过本地忽略文件导入环境变量，不进入报告。

验收结束后，这三个临时容器已全部停止，测试卷保留，其他应用与容器未改动；见[资源清理记录](resource-cleanup.json)。

单元命令：

```powershell
mvn -B -ntp -pl :shortlink-command,:shortlink-redirect -am test -DargLine=-Xmx512m
D:/miniconda/python.exe -m unittest discover -s scripts/performance -p test_repository_jar_paths.py
```

本次实际分服务运行单元以限制资源占用。集成测试分别用下列类选择器运行；先提供对应测试类要求的环境变量及明确 reset 确认，未提供时用例会跳过，不能视作验收：

```powershell
mvn -B -ntp -pl :route-membership -am -Pintegration -Dit.test=JdbcRouteMembershipMySqlIT -Dfailsafe.failIfNoSpecifiedTests=false -DargLine=-Xmx512m test-compile failsafe:integration-test failsafe:verify
mvn -B -ntp -pl :shortlink-command -am -Pintegration '-Dit.test=CommandBusinessIntegrationTest,BatchMetadataIntegrationTest' -Dfailsafe.failIfNoSpecifiedTests=false -DargLine=-Xmx512m test-compile failsafe:integration-test failsafe:verify
mvn -B -ntp -pl :shortlink-redirect -am -Pintegration '-Dit.test=RouteMembershipCacheIT,MembershipHintConsumerIT' -Dfailsafe.failIfNoSpecifiedTests=false -DargLine=-Xmx512m test failsafe:integration-test failsafe:verify
```

共享协议使用 `MEMBERSHIP_IT_*`，Command 使用 `SHORTLINK_BUSINESS_TEST_*` / `SHORTLINK_BATCH_TEST_*`，Redirect 使用 `BLOOM_REDIRECT_IT_*`，Kafka 使用 `BLOOM_HINT_IT_BOOTSTRAP`。严格隔离地址、库名和清理范围的断言保留在各测试中。新建业务测试库先执行 `001-business-schema.sql` 与 `004-route-membership.sql`；共享协议套件自己创建最小测试表。

验收过程中修复了真实 MySQL SELECT-only 账号不能执行 `FOR UPDATE` 的问题，最终改为读侧 `FOR SHARE`、写侧保留 `FOR UPDATE`，并增加两个并发读租约与写入互斥的真实数据库测试。其余初次失败来自新增 Mockito 夹具，最终重跑全部相关套件通过。关键源码的换行归一化校验值见 [source-sha256.json](source-sha256.json)。

## 启用边界

新表默认 OFF，Redirect 本地开关默认 false。部署前执行 DDL、升级全部写入口，然后受控 `init`、SHADOW 验证、`enforce`；详见[部署与恢复](../../development/route-membership.md)。创建默认增加约 1.25 秒加调度及数据库耗时，发布预算 4 秒；没有将这一延迟代价隐藏为零成本优化。

本次不宣称创建后所有负缓存立即一致：原数据库 NOT_FOUND 缓存仍有原权威 TTL。Bloom 不额外误拒漏登记新链。进入 COMMIT 后的超时仍可能结果未知，按原 requestId / 持久任务 ID 恢复。数据库必须单主写入，不允许在线静默回滚已确认的登记和路由事实。

完整网关 E2E、持续创建吞吐、攻击混合负载、长时间容量增长与 QPS 上限留待后续独立验收。当前结果只证明上述功能边界，不作为性能成绩。
