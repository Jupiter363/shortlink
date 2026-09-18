# 投放分析 Agent 提数链路修复验收

关联 Issue #59。本次只处理统计工具取数失败及本地运行配额，后续上下文和报告内容优化仍留在该 Issue。

## 故障证据

2026-09-18 23:34:10（Asia/Shanghai）的 ClickHouse `system.query_log` 中，统计聚合与共享 receipt proof 查询均报 `241 / MEMORY_LIMIT_EXCEEDED`，涉及服务器总内存与 OvercommitTracker。两次查询各自内存约为 17.9 MB、0.7 MB，均未达到原 API 单查询 256 MiB 上限。原服务总内存上限为 1 GiB，进程 RSS 已接近该值；后台系统日志合并也在运行。

因此，本次已复现故障的原因是 ClickHouse 服务整体内存压力。Worker readiness、Flink RUNNING、`SELECT 1` 成功都不能代替业务统计查询验收。

## 最终配置

按用户最终要求采用较宽松的有限预算。ClickHouse 容器 4 GiB、服务 3 GiB、同步和异步 API 查询各 1 GiB；保留返回行数、字节与期限合同。服务总上限与容器 OOM 边界之间预留 1 GiB。CPU 未新增 Docker 配额。

| 本地服务 | 容器内存上限 |
| --- | ---: |
| ClickHouse | 4096 MiB |
| MySQL、Kafka、Flink JobManager | 各 1024 MiB |
| Flink TaskManager | 1536 MiB |
| Admin、Agent、Analytics API、Analytics Worker | 各 768 MiB |
| APISIX、Kafka Connect | 各 640 MiB |
| Command、Redirect | 各 512 MiB |
| MinIO | 384 MiB |
| Redis | 256 MiB |
| Frontend | 128 MiB |

16 个容器内存上限之和为 14.5 GiB，本机 Docker VM 可见内存约 15.55 GiB。上限不是预分配量，也不构成任意负载下不会 OOM 的保证。JVM 堆、Flink 进程配置维持原配置。

配置已同步至本机忽略文件 `.work/local-dev/compose.yaml`、`analytics.compose.json`、`setup-analytics.py`，并经 Compose 配置校验。ClickHouse 生成配置引用已入库的 `deploy/clickhouse/development-memory.xml` 与 `development-users.xml`，避免后续生成恢复旧的 1 GiB 服务上限。容器通过 `docker update` 在线扩容，无容器重建或业务卷迁移，原 D 盘 Docker 数据保留。

## 代码与回归验证

- 同步查询同时检查 HTTP 状态与 `X-ClickHouse-Exception-Code`，避免把 HTTP 200 携带异常头误当成功。241 使用固定容量诊断文本，并保留 `UNAVAILABLE` 错误码。
- Admin 仅对白名单中的固定诊断文本补充原因，不传递原始 SQL、异常正文或凭据。异步任务错误码保持原合同。
- Analytics API 真实本地 HTTP server 测试 38/38 通过，涵盖异常头、错误正文隔离、1 GiB 请求参数、行数及期限约束。
- Admin 错误分类、工具控制器、MVC 与任务回归测试 26/26 通过；合计 64 项。
- 两服务构建成功并已部署。最终 Analytics API JAR SHA-256：`7E38CAAA1FEFE636648AF3E23AB0FE3F9E3020DD576A837C8E042E681739562A`。

## 真实页面与运行状态

2026-09-18 23:59:16 在内置浏览器提交：

> 分析默认分组在 2026-09-12 至 2026-09-18（Asia/Shanghai）的访问趋势和高峰时段，并抽查访问记录。

页面中 `get_group_stats`、`get_group_access_records` 均显示已完成，返回两张证据卡；统计 PV 13、UV 13（近似）、UIP 1（近似），明细返回 10 行且存在下一页。查询结束边界为 2026-09-19 00:00:00，不包含该边界。

检查 ClickHouse 查询日志，自最终服务更新窗口 23:58:00 起至本次验收结束，新增 241 为 0。16 个容器均运行，已配置健康检查的服务均 healthy，Analytics API actuator 为 UP。

操作期间创建 Flink 保存点并原样恢复同一 build、epoch、消费组。新 job `142dd8e4cfe061c029be834bb7d362b6` 为 RUNNING，6/6 tasks 运行；恢复记录指向保存点 `savepoint-f672ac-f7543919d0fc`，至少 4 个新检查点成功、0 失败。没有启动重建、重跑初始化或清理历史数据。

数据仍为 `PARTIAL / UNKNOWN / PRODUCER_SAMPLE_STALE`。这次验证证明取数工具链路恢复，不代表历史覆盖已完整，也不代表模型输出的每个推断都已验收；这些继续由 Issue #59 和相应数据质量工作跟进。
