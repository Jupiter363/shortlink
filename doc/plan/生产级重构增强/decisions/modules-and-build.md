# 模块、来源与构建冻结（v0.7 实施记录）

本记录补充 M0-04 的实现决策。运行模块和依赖方向以根 POM 与 README 为准，`project` / `aggregation` 已退役。没有另起 Leaf Server，也没有把统计持久状态放回业务库或 Agent。

| 项目 | 冻结值 / 边界 | 已执行验证 |
| --- | --- | --- |
| Java / Maven | Java 17、Maven 3.9.4、UTF-8；父构建统一 release=17 | Windows 构建与原生 MySQL 集成；Flink 另在 Linux Java 17 验证 |
| Spring | 保持 Boot 3.0.7 / Cloud 2022.0.3 / Alibaba 2022.0.0.0 原有主线 | 业务模块编译与组件测试；逐 JAR 启动验收单独记录 |
| ShardingSphere | 5.3.2；一个物理 ds_0，user/group 按 username、link 按 gid，各 16 张表 | 实际 ShardingSphere/MySQL 事务回滚、AES 字段与同物理分表移组 |
| ID | 嵌入式 Segment 适配，Current/Next、预取、独立事务、可选有界动态 Step | 并发区间、ACK 未知不重用、真实 MySQL 分配器集成 |
| Kafka | 客户端 / Broker 3.9.0；raw LogAppendTime | 真实 Broker 时间类型、生产/消费、Flink checkpoint/EOS、独立结果流 |
| Flink | 1.20.3，Kafka connector 3.4.0-1.20 | Linux 真 MiniCluster、RocksDB 和 Kafka；Windows DLL 环境失败未替换为假验证 |
| CH / Connect | CH 验收 25.3.6.56，官方 Connect 1.4.0 | 官方插件真投递、重复输入、异常任务失败和覆盖查询 |
| APISIX | 3.11.0，自定义边界与请求结果插件 | 实际路由 HTTP、TLS/SNI、请求头和非法路径组件验收 |

## Leaf 复用边界

来源：`https://github.com/Meituan-Dianping/Leaf`，完整提交 `86a6441d263497b9f9ee321de13422b9c63f0c06`。`id-generator/upstream/` 保存逐文件可核验来源，清单、SHA-256、上游 Apache 2.0 许可和版权说明在模块内。完整说明见 `id-generator/UPSTREAM.md` 和 `id-generator/patches/`。

本项目保留并适配 Segment 的数据库号段语义、双段与异步预取，使用短临界区对整体区间预留进行仲裁。它不是未经修改的 Leaf Core：上游逐号游标/RWLock 被区间接口替换，独立的数据库超时、ACK 不确定处理、执行器预算和动态 Step 状态机由本项目维护。未引入 Leaf Server、ZooKeeper 或 Snowflake。选择仍是嵌入式 Segment 架构；实际改动边界公开，不用“直接复用 Leaf”掩盖新增维护责任。

全局编号 52 位；Feistel 固定映射后输出 9 位 Base62。固定 codec/version/key 指纹登记在 `t_id_namespace`，不同配置拒绝启动。全局 `(domain_norm,short_uri)` 二进制唯一约束和永久路由墓碑是最终防线；不通过无界重试碰撞来产生短码。外部短码不能承诺密码学不可猜测。

## 状态与打包边界

- 业务 DB：全局用户身份、16 分表、路由、初始化/会话清理意图、策略、批量/元数据任务、Outbox、恢复动作门。
- 独立统计控制 DB：原始归档进度、不可变 build/manifest、覆盖、恢复世代、快照与查询任务。
- 独立 Agent DB：画像、审核、批次、Graph checkpoint 和 evidence；Command 是策略事实的唯一权威。
- 每个 Spring 业务进程生成 Boot 可执行 JAR；Flink 生成适合 Flink 集群的 shaded JAR，集群提供 Flink runtime。
- 管理接口的 Session/Feign 身份只在信任边界内传递；用户请求头、旧 checkpoint 或快照都不是当前授权证明。

## 验收范围

本轮执行单元及真实依赖的组件集成测试。默认 Surefire 与 `integration` Failsafe 按文件和 JUnit 标签双重排除 E2E/性能测试。没有压测结论，不将集群十万跳转 QPS 写为达到值；完整验收结果与保留条件见本目录后续验收记录。

## 服务发现模式

新服务以环境中明确提供的稳定内网 DNS/LB 地址绑定，不并行维护一份 Nacos 目录。原有 Nacos starter 暂留在历史管理/Agent 依赖中但生产模板关闭发现；其故障不会阻断新服务启动。APISIX 正式路由配置以 etcd 模式管理，standalone 文件模式用于可重复的组件测试。这个决策替代原计划中沿用旧 Nacos 发现目录的假设；未对 Nacos 集群作上线验收。
