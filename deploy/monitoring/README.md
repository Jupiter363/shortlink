# Admin / Redirect 监控

`prometheus.yml` 使用生产配置的本机管理端口 8102/8103，每 15 秒采集一次。
管理服务绑定 127.0.0.1，因此本模板要求 Prometheus 运行于同一主机网络命名空间；
多机器部署应在各主机部署采集进程或使用受控管理网络，并修改显式 target。
不要为了容器 localhost 的差异把 Actuator 暴露到公网。

Java Gateway 删除后，管理入口的采集目标改为 Admin，避免持续抓取已经撤除的 8100 端口。
Admin 与 Redirect 通过 Spring Boot Actuator 暴露以下指标；生产启动后应再核对实际输出：

| 服务 | 实际指标 |
|---|---|
| Admin、Redirect | `jvm_memory_used_bytes`、`jvm_memory_max_bytes`、`process_cpu_usage`、`http_server_requests_seconds_count` |
| Redirect | `hikaricp_connections_pending`、`hikaricp_connections_active`、`hikaricp_connections_timeout_total` |
| Redirect 事件发送 | `shortlink_events_failed_total`、`shortlink_events_rejected_total`、`shortlink_events_delivered_total`、`shortlink_events_pending_bytes`，均带 click/result lane |
| Prometheus 自身 | `up` 表示 scrape 成功，不代表业务依赖 Ready |

`alerts.yml` 提供采集不可达、持续堆内存压力、权威库连接池等待和事件采集损失四条基础告警。
85%/5 分钟等是初始运维阈值，需随正式内存和池预算调整，不是性能验收成绩。
事件 rejected/failed 属于真实损失；Agent 质量读取仍以持久质量证据为准，不能用告警替代数据完整性协议。
`/actuator/health` 的依赖健康必须单独作为部署就绪检查；`up=1` 或 liveness UP 均不足以替代它。

用官方 Prometheus `promtool` 校验配置和四条规则：

```sh
docker run --rm --entrypoint /bin/promtool \
  -v "$PWD/deploy/monitoring:/etc/prometheus:ro" \
  prom/prometheus:v3.2.1 check config /etc/prometheus/prometheus.yml
```

历史组件验证包含真实 `/actuator/prometheus` 输出和 promtool 语法检查；切换为 Admin 后，
需要重新核对目标可达性与输出。告警通知通道、生产流量 SLO 及完整数据库/Kafka lag 监控
另行验证。其它依赖观测由部署总文档统一说明。
