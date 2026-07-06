# 短链接项目：智能投放与分析 Agent 外部方案调研（正式版）

> 文档性质：外部方案调研、产品能力对齐、第一阶段技术路线结论  
> 适用范围：短链接项目智能投放与分析 Agent 第一阶段建设；安全风控 Agent 作为第二阶段能力线预留  
> 当前任务边界：本文档用于指导后续设计和开发，不声明当前代码已经实现 Agent 能力  
> 已确认技术路线：第一阶段采用 **Java / Spring Boot / Spring AI Alibaba Agent Framework + Graph**，不采用 Python/FastAPI 独立 Agent Runtime  
> 核心原则：先构建受控 Agent 能力，再通过现有 `admin` 模块增量接入；不重构 `project` 跳转热路径，不把短链接系统改造成完整广告投放平台

---

## 0. 文档定位

本文档把成熟短链接、深度链接、归因分析、营销触达和 URL 安全产品的能力拆解为本项目可落地的 Agent 设计输入。

当前项目已经具备：

```text
短链接创建；
短链接批量创建；
分组管理；
短链接跳转；
PV / UV / UIP 统计；
小时、星期、地区、设备、浏览器、操作系统、网络类型画像；
访问明细；
回收站；
后台管理与 Feign 聚合。
```

第一阶段不建设广告账户、预算投放、转化归因或智能路由，而是把现有短链接与统计能力包装成受控工具，让 Agent 完成：

```text
理解投放/活动分析需求；
查询用户自己的分组、短链和统计数据；
生成趋势、异常、机会点和复盘建议；
生成单条或批量短链创建草案；
所有写操作进入 PendingAction，人确认后再执行；
用 Graph 固化流程，用 LLM 做解释、归纳和结构化生成。
```

---

## 1. 已确认的框架结论

### 1.1 第一阶段选型

```text
Agent Runtime：Spring AI Alibaba Agent Framework + Graph
Workflow Runtime：Spring AI Alibaba Graph
Agent 形态：顶级 Maven module `agent-service`
业务工具边界：agent-service 内部 Agent Tool Facade + business API，底层复用 admin 现有接口
业务事实源：project 短链接与统计服务
默认模型：DeepSeek V4 Flash，密钥通过环境变量注入
首期交付顺序：先 API 联调，再交付 agent-service 独立 Agent Console
Graph checkpoint：第一版直接落 MySQL
MCP：当前不引入，避免为少量内部工具增加远程协议复杂度
```

### 1.2 选择原因

Spring AI Alibaba 官方 README 将其定位为面向 Agentic、Workflow、Multi-agent 应用的生产级 Java 框架，并明确包含 Agent Framework、Graph、Admin、Studio 等组件。其 README 说明：

- Agent Framework 内置 Context Engineering、Human In The Loop，并提供 `SequentialAgent`、`ParallelAgent`、`RoutingAgent`、`LoopAgent` 等工作流模式。
- Graph 是 Agent Framework 的底层运行时，提供长任务、有状态 Agent 所需的持久化、工作流编排和流式输出能力。
- Graph 支持条件路由、嵌套图、并行执行、状态管理，并可导出 PlantUML / Mermaid。

参考链接：

- [Spring AI Alibaba GitHub](https://github.com/alibaba/spring-ai-alibaba)
- [Spring AI Alibaba Graph Docs](https://java2ai.com/docs/frameworks/graph-core/quick-start/)
- [Spring AI Alibaba Agent Framework Docs](https://java2ai.com/docs/frameworks/agent-framework/tutorials/agents)
- [Spring AI Tool Calling](https://docs.spring.io/spring-ai/reference/api/tools.html)

### 1.3 对本项目的含义

原先“Python Agent Service + Admin Adapter”路线在正式版中收束为：

```text
Java agent-service
  -> Spring AI Alibaba Graph 编排
  -> ReactAgent / ChatModel 做分析解释
  -> Spring AI Tool / ToolCallback 调用进程内 Agent Tool Facade
  -> Business API 复用 admin 现有接口和必要 internal API
  -> Graph interrupt / PendingAction 完成人工确认
  -> MySQL / 日志记录会话、工具轨迹和待确认动作
```

这条路线与当前 Java/Spring 项目更贴合，减少部署语言栈，同时保留 Graph、HITL、工具调用、流式执行、可观测和后续多 Agent 扩展能力。

---

## 2. 外部产品能力调研

### 2.1 Bitly

参考资料：

- [Bitly Assist](https://bitly.com/pages/ai/bitly-assist)
- [Bitly AI Connections Platform](https://bitly.com/pages/ai)
- [Bitly LLM Integrations](https://bitly.com/pages/products/llm-integrations)

可借鉴能力：

| 能力 | 外部做法 | 本项目第一阶段落地点 |
|---|---|---|
| 对话式分析 | 用户用自然语言询问链接、二维码和活动表现 | Graph 调用统计工具，输出趋势、异常和建议 |
| 对话式创建 | 根据描述生成链接、标题、标签、二维码 | Agent 解析活动、渠道、URL、有效期，生成 PendingAction |
| 批量处理 | 批量生成多个链接或处理多个资产 | 复用现有 `batchCreateShortLink`，加入数量上限和确认 |
| 外部集成 | 让 LLM 或自动化工具创建可追踪短链 | 当前不做外部工具协议，先通过 Agent Console/admin 接入 |
| 品牌与标签 | 品牌域名、标签、campaign metadata | 第一阶段用 `gid` 表示活动池，用 `describe` 承载轻量元数据 |

对本项目的启发：

```text
Agent 不只是聊天问答，而是链接资产操作入口。
回答必须绑定真实统计数据，不能让 LLM 自行编造指标。
创建动作必须可预览、可确认、可追溯。
```

### 2.2 Short.io

参考资料：

- [Short.io Pricing and AI Assistant](https://short.io/pricing)
- [Short.io Features](https://short.io/features)

可借鉴能力：

| 能力 | 外部做法 | 本项目第一阶段落地点 |
|---|---|---|
| AI Assistant | 辅助链接管理和分析 | 提供 `chat + graph + tool + insight cards` |
| Link Analytics | 点击、访客、设备、地区、来源统计 | 复用 `ShortLinkStatsRespDTO` 维度 |
| UTM Builder | 自动生成 campaign/source/medium 参数 | Agent 创建短链时可建议 UTM，但必须用户确认 |
| Mobile Targeting | 不同设备跳转不同目标 | 第二阶段智能路由预留，不进入第一阶段热路径 |
| A/B Testing | 多目标或多链接对比 | 第一阶段做分析建议，第二阶段引入实验模型 |

对本项目的启发：

```text
第一阶段应围绕“统计解释 + 创建效率”。
设备、地区、网络等已有画像可以直接用于建议。
智能路由、A/B、转化优化属于第二阶段，避免第一期过载。
```

### 2.3 Rebrandly

参考资料：

- [Rebrandly Features](https://www.rebrandly.com/features)
- [Rebrandly API Documentation](https://developers.rebrandly.com/)

可借鉴能力：

| 能力 | 外部做法 | 本项目第一阶段落地点 |
|---|---|---|
| Branded Links | 品牌域名和链接资产管理 | 当前 `short-link.domain.default` 作为基础，后续支持多域名 |
| UTM 管理 | 将渠道参数标准化 | Agent 生成 `utm_source/utm_medium/utm_campaign` 建议 |
| API First | 通过 API 管理链接、域名和统计 | 通过 `admin agent adapter` 封装现有 Feign/API |
| Analytics | 链接效果追踪 | 复用 PV/UV/UIP、小时、星期、地域、Top IP 等 |

对本项目的启发：

```text
第一阶段不必新增完整 campaign 表，但文档和接口命名要预留 campaign 概念。
UTM 是低成本高收益能力，可作为 Agent 创建短链时的可选建议。
```

### 2.4 Branch

参考资料：

- [Branch Mobile Linking Platform](https://www.branch.io/platform/mobile-linking/)
- [Branch Attribution](https://www.branch.io/platform/attribution/)

可借鉴能力：

| 能力 | 外部做法 | 本项目第一阶段落地点 |
|---|---|---|
| Deep Linking | 根据设备、App 安装状态、上下文跳转 | 第二阶段智能路由；第一阶段只做规划建议 |
| Attribution | 归因来源和渠道效果 | 当前缺少转化事件，第一阶段只分析点击侧指标 |
| Cross-platform Journey | Web/App/渠道统一链路 | 后续新增 `delivery_rule` 和 `decision_log` |

对本项目的启发：

```text
“投放效果好不好”不能长期只看点击。
但当前系统没有转化事件，所以第一阶段必须明确只做点击/访问质量分析。
不得宣称 ROI、CPA、GMV 或支付转化优化。
```

### 2.5 AppsFlyer OneLink

参考资料：

- [AppsFlyer OneLink](https://www.appsflyer.com/products/customer-experience-deep-linking/onelink/)
- [AppsFlyer Deep Linking Documentation](https://dev.appsflyer.com/hc/docs/dl_overview)

可借鉴能力：

| 能力 | 外部做法 | 本项目第一阶段落地点 |
|---|---|---|
| OneLink | 一个链接按平台和上下文路由 | 第二阶段智能路由能力线 |
| Attribution + Deep Linking | 跳转与归因结合 | 后续新增转化事件与归因窗口 |
| Smart Script | Web 到 App 的参数传递 | 后续支持渠道参数透传和落地页脚本 |

对本项目的启发：

```text
Agent 可以先帮助创建标准化参数链接。
真正智能投放必须逐步引入“点击后事件”，否则只能做访问分析。
```

### 2.6 国内短信与营销短链方案

参考资料：

- [阿里云短信服务](https://help.aliyun.com/)
- [火山引擎短信服务](https://www.volcengine.com/docs)
- [云片](https://www.yunpian.com/)
- [百度短网址](https://dwz.cn/)

可借鉴方向：

| 能力 | 国内常见做法 | 本项目第一阶段落地点 |
|---|---|---|
| 短信营销短链 | 长链接转短，降低短信长度，追踪点击 | Agent 生成带渠道/批次描述的短链 |
| 点击统计 | 统计点击量、点击人数、点击时间 | 现有统计已覆盖核心点击指标 |
| 合规审核 | 域名、内容、模板、跳转目标审核 | 第二阶段安全风控 Agent |
| 回调/报表 | 给业务系统回传点击事件 | 后续用 Redis Stream / HTTP callback 扩展 |
| 手机号级追踪 | 按手机号生成或映射短链 | 第一阶段不做，涉及隐私和合规 |

对本项目的启发：

```text
Agent 能力必须考虑合规边界。
不要把手机号、完整 IP、UV cookie、访问明细无脱敏注入模型。
安全风控 Agent 应成为创建短链前的能力线，但第一阶段先预留接口与设计。
```

### 2.7 URL Reputation 与安全扫描

参考资料：

- [Cloudflare Radar URL Scanner](https://developers.cloudflare.com/radar/investigate/url-scanner/)
- [Google Safe Browsing API](https://developers.google.com/safe-browsing)
- [VirusTotal URL API](https://docs.virustotal.com/reference/urls)

可借鉴能力：

| 能力 | 外部做法 | 本项目落地点 |
|---|---|---|
| URL 扫描 | 检测网页内容、加载资源、跳转链路 | 第二阶段 `UrlRiskAgent` |
| 威胁情报 | 判断恶意、钓鱼、欺诈、可疑域名 | 创建短链前风险评分 |
| 结果解释 | 给出风险原因与证据 | 风控 Agent 输出可审核风险报告 |
| 自动拦截 | 高风险链接阻断或人工审核 | 后续接入 `enable_status` / 审核表 |

对本项目的启发：

```text
安全风控不能放在跳转热路径做大模型推理。
更适合在短链创建、定期复检、异常访问告警三个场景运行。
第一阶段只预留风险接口，不强制接入外部扫描服务。
```

---

## 3. 成熟方案共性总结

### 3.1 成熟产品不只做短链，而是做链接资产管理

成熟产品围绕以下对象组织能力：

```text
Link
Campaign
Channel
Domain
QR Code
UTM
Audience
Attribution
Report
Risk Review
```

第一阶段低侵入映射：

| 外部对象 | 第一阶段映射 | 后续正式对象 |
|---|---|---|
| Campaign | `gid` 分组 | `t_agent_campaign` |
| Channel | `describe` 中的结构化片段 | `channel` 字段或渠道表 |
| Link | `t_link` | 继续复用 |
| Report | Agent 输出的报告卡片 | `t_agent_report` |
| Recommendation | Agent 回答中的建议 | `t_agent_recommendation` |
| Risk Review | 预留接口，不强制启用 | `t_url_risk_review` |

### 3.2 AI 用于理解、解释和编排，不替代事实源

成熟产品中的 AI 通常用于：

```text
自然语言入口；
自动生成链接、参数、标签；
统计数据摘要；
异常和机会识别；
报告生成；
操作建议。
```

本项目红线：

```text
LLM 不能直接生成事实指标；
LLM 不能越权读取用户分组；
LLM 不能直接删除、回收、禁用短链；
LLM 不能绕过确认创建批量短链；
LLM 回答必须引用工具返回的数据快照。
```

### 3.3 真正投放优化需要转化事件，但第一阶段先做点击智能

Branch、AppsFlyer 这类产品的核心价值在归因和转化，而当前项目只有点击侧统计。因此第一阶段目标写清楚：

```text
支持点击表现分析；
支持访问画像解释；
支持活动短链创建；
支持低风险投放建议；
不宣称 ROI、CPA、GMV、支付转化率优化。
```

---

## 4. 第一阶段产品定位

产品名称：

```text
短链接智能投放与分析 Agent
ShortLink Campaign Analyst Agent
```

一句话定位：

```text
面向短链接后台用户的智能运营助手，以现有分组、短链和统计接口为事实源，通过 Spring AI Alibaba Graph 编排自然语言分析、短链创建草案、活动效果解释、异常识别和复盘建议，并通过 PendingAction 与人工确认保护所有写操作。
```

第一阶段主对象：

```text
AgentSession
AgentMessage
GraphRun
ToolCallTrace
PendingAction
StatsSnapshot
InsightCard
ShortLink / Group / StatsSnapshot
```

第一阶段不做：

```text
不改造跳转热路径；
不新增智能路由；
不接广告平台预算；
不做 ROI / CPA / GMV；
不做手机号级追踪；
不强制接入安全风控 Agent；
不替代原后台列表与统计页；
不把 Graph 状态作为业务事实源。
```

---

## 5. Spring AI Alibaba Graph 能力映射

| 框架能力 | 官方能力边界 | 本项目落地方式 |
|---|---|---|
| Agent Framework | ReactAgent、工具调用、多轮、结构化输出 | Campaign Analyst Agent 负责解释、总结、建议 |
| Graph | 节点、边、条件路由、状态、流式、持久化 | 固化分析/创建/确认流程 |
| Human In The Loop | interrupt / 人工输入后恢复 | PendingAction 生成后暂停，确认后继续执行 |
| Tool Calling | 模型提出工具调用，应用侧执行 | 工具由 agent-service 内部 Tool Facade 执行，不直连数据库 |
| Parallel / Sequential / Routing / Loop | 内置常见工作流模式 | 分析链路可顺序执行，统计维度可并行查询 |
| Context Engineering | 上下文压缩、工具限制、重试、动态工具选择 | 限制访问明细、控制批量数量、错误降级 |
| Studio / Admin | 可视化开发、观测、评估、Prompt 管理 | 后续用于调试、评测和可观测，不作为第一版强依赖 |
| MCP | Client / Server 工具协议 | 已评估，当前 Agent 不需要外部工具协议，第一阶段和近期演进均不引入 |

---

## 6. 推荐第一阶段结论

正式结论如下：

```text
第一阶段采用 Spring AI Alibaba Agent Framework + Graph。
新增 Java agent-service 或独立 Maven 模块作为 Agent Runtime。
Graph 固化智能投放与分析流程。
ReactAgent / ChatModel 只负责理解、解释、总结和结构化生成。
agent-service 内部 Agent Tool Facade 是受控工具边界，负责工具 schema、限流、脱敏、PendingAction 和审计；business API 负责把工具意图转换为短链业务能力；admin 侧只补充必要 internal API。
Project 保持短链创建、跳转和统计事实源职责。
所有写操作必须先生成 PendingAction，用户确认后才执行。
安全风控 Agent、智能路由、转化归因、A/B 实验进入第二阶段。
```

已确认的补充结论：

1. 第一阶段模型供应商默认使用 DeepSeek V4 Flash。
2. DeepSeek API Key 只允许通过 `DEEPSEEK_API_KEY` 或 `LLM_API_KEY` 注入，不写入文档、代码、Git、Prompt 或日志。
3. 第一阶段先完成 API 联调，随后在 `agent-service` 内交付独立 Agent Console。
4. `agent-service` 作为顶级 Maven module 独立建设，不先塞入 `admin` 包内做 POC。
5. Graph checkpoint 第一版直接落 MySQL，不使用纯内存方案作为正式验收目标。
