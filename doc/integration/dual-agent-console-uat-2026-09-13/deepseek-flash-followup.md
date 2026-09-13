# DeepSeek Flash 真实调用补验（2026-09-13）

**官方 Flash 接入通过，两个 Agent 均已产生真实模型回答。** 本轮解除先前“未配置 LLM key”的阻断，同时修复启用真实模型后暴露的 Admin 5 秒超时。Analytics 服务缺失、风险画像候选发现失败仍保留，完整统计与风控业务 UAT 仍为条件通过。

## 配置与范围

- 官方端点：`https://api.deepseek.com`；模型：`deepseek-flash`。凭据请求官方 `/models` 返回 200，可用列表包含该标识；标识与 [DeepSeek 官方发布说明](https://www.deepseek.com/en/news/deepseek-v4-1-flash/)一致。
- 密钥仅写入被 `.gitignore` 排除的 `.work/local-dev/deepseek.env`，由 Compose 的 Agent `env_file` 加载。没有写入前端、Java 源码、验收报告或可提交的配置。
- 仅重建本地 Agent、Admin 容器以加载环境；没有重建运行中的 JAR。当前 Admin 使用与源码一致的 `SPRING_APPLICATION_JSON` 客户端超时覆盖。
- 本轮时间为 21:18–21:26（Asia/Shanghai），网关日志和 checkpoint 更新时间使用 UTC。
- 两个 Graph 使用确定性工具节点和无工具回调的模型解释节点。本轮不构成 DeepSeek 原生多轮 Tool Calls 的验收。

## 实测结果

| 场景 | 网关结果 / 耗时 | 业务证据 |
| --- | --- | --- |
| 投放分析，100 字内分组摘要 | 200 / 2.028 秒 | 真正读取 1 个分组、1 条短链；`llm_analysis` 1489ms；checkpoint FINISHED |
| 安全风控，100 字内证据充分性说明 | 200 / 2.492 秒 | `provider=deepseek`、`model=deepseek-flash`、`finishReason=stop`；模型明确指出缺少画像/统计，不能据此判断安全；checkpoint FINISHED |
| 修复前，“分析默认分组近 7 天表现” | **503 / 5.064 秒** | Admin 日志为 SocketTimeoutException；Agent 后续仍生成回答并保存 FINISHED，证实客户端提前超时 |
| 修复后，受控暂停 Agent 再恢复 | 200 / 13.740 秒 | 请求在 11 秒后仍处于执行态，恢复后返回真实分组摘要；同时跨过旧 Admin 5 秒和 APISIX 10 秒等待限制 |
| 修复后，相同默认预设、新会话 | 200 / 6.959 秒 | `model=deepseek-flash`、`finishReason=stop`，warnings 为空，`llm_analysis` 6763ms，checkpoint FINISHED |

表中耗时仅为这几次有限功能请求的网关日志值，不是性能分位数或容量结论。初次登录会话失效返回的 401 已通过重新登录解决，不计为模型调用失败。

## 超时修复

| 链路 | 修复后读取超时 |
| --- | --- |
| Agent → DeepSeek | 保持 30 秒，输出上限保持 2000 tokens |
| Admin `agent-chat` Feign 客户端 | 独立 45 秒，连接 1 秒 |
| APISIX 精确 `/api/short-link/admin/v1/agent/chat` | 独立 50 秒，连接 1 秒、发送 3 秒、重试 0 |
| Admin 通用 Feign | 保持 5 秒 |
| APISIX 普通管理 / 跳转 | 保持 10 秒 / 1 秒 |

新 chat 路由优先级 110，保留管理 Host、可信入口处理、请求体转发设置和限流保护。etcd 导入与 TLS 同源派生检查同步覆盖第三条路由。当前工作配置已生效，源码中的 Admin 属性会随下次正常构建进入 JAR。

验证：`AgentRemoteServiceFeignTest` 3/3 通过；网关拓扑离线测试 17/17 通过；限流/TLS/导入静态检查 4/4 通过。真实 13.740 秒延迟请求用于确认运行时确实应用了专用超时，不能由单纯配置文本断言替代。

收尾检查：新对话路由的未登录 POST 仍返回 401；既有短链的 HEAD 返回 302 和正确 Location；Agent、Admin 均为 healthy，APISIX、Frontend 持续运行。

## 保留的业务限制

- 正常投放预设目前只执行 `list_groups`，没有获得近 7 日统计。模型文字中提出的统计工具调用是说明性文本，没有实际执行，且包含尚未核对的工具名；不能把这段文字当作统计分析已完成。
- 本地 Analytics API、Worker、Flink、ClickHouse 仍未启动。风险画像候选发现的授权冲突没有在本轮修改。
- 安全风控模型解释通过，不代表风险画像、证据查询、审核和策略执行闭环通过。本轮没有策略执行回执。
- 当前无工具模型解释沿用供应商默认 thinking 设置；模型原生多轮工具调用所需的 `reasoning_content` 回传尚未验收。
- 历史无密钥烟测收据保持原样，以本补验及 [结构化收据](deepseek-flash-followup.json)反映新的模型状态。

[返回本轮 UAT](README.md)
