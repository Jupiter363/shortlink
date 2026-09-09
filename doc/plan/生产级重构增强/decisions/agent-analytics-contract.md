# Agent 与 Analytics / Command 契约

适用 v0.7 新环境建库部署；不做生产切流，不兼容历史明文密码和无身份画像。本轮只验收单元与集成测试。

交互 Tool、运营统计页和定时画像统一经 Admin 当前账号授权，再访问 Analytics API。Agent 不消费 Kafka，不拥有 Analytics Inbox，不直查 ClickHouse，不写策略 Redis。Kafka 采集、重放和窗口构建由独立分析链负责。

网关身份 x-shortlink-tenant-id/username/auth-version 与内部服务凭证必须同时通过；Admin→Agent 的 X-Agent-UserId/Username/Auth-Version 只能取自当前主体。正文 username、模型参数 gid 不是权限。系统批次仅允许明确配置的 SYSTEM username，由 Admin 读取 DB 映射当前账号版本，禁止无身份和开发旁路。

StatsEnvelope 包含 metrics/items/meta。三窗口 2h、24h、7d 一次查询，同 effectiveEnd；每页固定 snapshotId/recoveryEpoch/manifestVersion/sourceCut/metricVersion/detailDatasetVersion 并重新授权。meta 保留来源创建时间、实际截止时间、freshness/completeness/provisional/approximation/collectionQuality/availability/missingMetrics。manifestVersion 和 sourceCut 保留映射结构。PV/UV/UIP 全链路 Java long、SQL BIGINT；缺失、负数、小数和溢出均失败，绝不空值变零。统计旧 URL 保留，响应升级 envelope，客户端必须展示实际截止时间和质量。

画像读取按 tenantId、稳定 linkId 和当前 ownership scope；检查点线程键包含 username/tenant/authVersion/session，新请求清空旧 Tool/卡片状态，Spring AI callback 适配器保留可信 principal。自由文本 gid、旧 checkpoint、无 evidence 画像不能越过授权进入 LLM。历史画像展示地址按当前授权 linkId 重新解析；short/group 画像写入受租约和 evidence_created_at、profile_window_end 同时约束，旧证据不能覆盖新证据。单 scope≤500，七天查询≤10000行；定时源按 afterLinkId/ownershipVersion 显式翻页、每 scope 统计快照固定，并要求所有 scope 的 recoveryEpoch/effectiveEnd/manifestVersion/sourceCut/metricVersion/detailDatasetVersion 一致。预算溢出或变更返回 TOO_LARGE/失败，不伪装截断成功。

Agent 只保存画像、审阅、审计。策略事实和持久命令回执来自 Command。自动 LIMIT_RATE 带稳定 commandId/linkId、expectedPolicyRevision 和原始 evidence(snapshotId,recoveryEpoch,effectiveEnd,evidenceCreatedAt,executeBefore)。先按当前权限读已提交 commandId，再校验旧证据。新命令 deadline≤min(effectiveEnd+120秒,snapshotCreatedAt+10分钟)，Command 再次检查版本/恢复代次/期限。人工明确 revoke 不受旧统计期限约束。规则 security-risk-v2 允许 provisional 的实时窗口，但必须完整、新鲜、collectionQuality=NORMAL、PV≥100且至少两条强信号有可证明的统计来源。TRAFFIC_SPIKE 要求 PV exact，PEAK_HOUR_BURST 要求 PV/peakHourShare exact，IP_CONCENTRATION 要求 topIpShare exact，HIGH_REPEAT_VISIT 要求 PV/UV exact 且 UV≥20。未标定误差边界的近似 UV 仅阻止消费 UV 的规则，不阻止两条精确 PV 规则；近似 IP 集中度同理不参与自动资格。普通展示保留近似元数据，实际链路 UNKNOWN 采集质量仍整体阻止自动动作。CONFLICT/EXPIRED/EVIDENCE_UNAVAILABLE 不展示为已生效。

异步统计工具 submit_statistics_query_job/get_statistics_query_job/get_statistics_query_job_page 继续经 Admin 转发 Analytics jobs，不新增 Agent Inbox/consumer。提交仅接受 METRICS（日统计）或 ACCESS_RECORDS、≤180天和≤500完整授权链接；请求标识稳定用于重试。QUEUED/RUNNING 返回 PENDING/resultReady=false，一次调用只发一次远程请求，不循环轮询，不把任务接收成功表述为统计已完成。后续请求使用 jobId 查询状态，SUCCEEDED 后按 pageIndex 逐页取结果，每次带当前主体并由 Analytics 重授权、检查 ownership/epoch。请求超预算明确失败；分页结果保留原始质量和 nextPageIndex，不能据单页伪造完整汇总。

新库使用 deploy/mysql/003-agent-analytics-adaptation.sql（与 Agent bootstrap 同步）；画像、风险事件/快照/审阅增加 tenant_id VARCHAR(32)、link_id BIGINT 与授权索引。旧无稳定身份的记录不可见。旧 RedisPublisher/JdbcRiskPolicyRepository 仅为拒绝写入的迁移守卫，无 Redis/策略表 I/O 及 Spring bean；新库不建 Agent 策略事实表。浏览器 console 走管理网关公共 Agent API、使用用户 session token，不持有内部服务令牌；JSON 大整数保留为精确十进制文本。

本轮验收以 Maven 默认单测和 integration profile 为准，E2E/performance 标签及文件名均排除。MySQL 集成仅接受 loopback 3306/13306 的独立 shortlink_agent_it；检查点恢复测试必须真实执行，不再以缺环境变量条件跳过冒充验收。
