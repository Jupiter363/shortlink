# 管理前端与 Agent 工作台

`frontend/console-vue/` 是当前标准管理入口。根路由进入短链接管理，两个 Agent 作为登录后的独立入口与短链接管理并列。

## 请求链路

```text
Browser -> Nginx /api -> APISIX (management host) -> Admin
                                                \-> Command / Analytics API / Agent Service
```

前端只访问同源 `/api`。Axios 统一注入现有登录态的 `Token` 与 `Username`；Agent chat body 只发送 `sessionId`、`agentType` 和 `message`。Admin 从已验证的 `UserContext` 构造可信主体，浏览器不能指定 Agent 的用户或内部服务身份。

主要路由：

| 页面 | 路由 | 登录要求 |
| --- | --- | --- |
| 登录 / 注册 | `/login` | 否 |
| 短链接管理 | `/home/space` | 是 |
| 投放分析 Agent | `/home/agent/campaign-analysis` | 是 |
| 安全风控 Agent | `/home/agent/security-risk` | 是 |
| Agent 兼容入口 | `/home/agent`（重定向至投放分析） | 是 |
| 个人信息 | `/home/account` | 是 |

Agent 工作台保留回答、洞察卡片、告警、风控动作、访问记录、Graph 执行轨迹和脱敏调试数据。两类 Agent 使用独立会话，路由切换会清除旧结果，请求期间会锁定会话和 Agent 选择，避免迟到响应串入另一条链路。风控动作按待人工确认、已执行和部分未生效分别展示。

健康入口只表示 Agent 服务可达，不代表 Graph、Analytics 或 LLM 分别就绪。未配置 `LLM_API_KEY` 时，实际对话会显示后端返回的配置错误，不能视为模型联调成功。当前双 Agent 验收结果见[2026-09-13 UAT 报告](../integration/dual-agent-console-uat-2026-09-13/README.md)。

当前本地 LLM 使用官方 `https://api.deepseek.com` 和模型 `deepseek-flash`，密钥通过被 Git 忽略的本地环境文件注入 Agent 服务；解释节点默认关闭 thinking，空回答或截断不会作为正常模型结果返回。真实统计、双 Agent 和后台分析结果见[业务链路补验](../integration/dual-agent-console-uat-2026-09-13/business-unblock-followup.md)。Agent 对话采用独立等待预算：模型读取 30 秒、Admin `agent-chat` 45 秒、APISIX 精确对话路由 50 秒；普通管理和短链跳转仍使用各自原有预算。

## 构建与代理

```bash
cd frontend/console-vue
npm ci
npm run lint
npm run build
```

开发服务器把 `/api` 代理到 `http://127.0.0.1:19080`，并将 Host 设置为 `admin.local.test` 进入 APISIX 管理路由。部署静态产物时，Nginx 需要：

1. 为未知页面路径回退 `index.html`；
2. 为 JavaScript、CSS 等资源返回正确 MIME；
3. 将 `/api/` 转发到 APISIX，并设置部署环境认可的管理 Host；
4. 不直接暴露 Admin、Command 或 Agent Service 的业务端口。

短链域名不在前端硬编码。创建请求将 `domain` 留空，由 Command 的 `shortlink.default-domain` 和允许域名集合统一决定；前端只负责展示服务端返回的地址。

`services/admin/.../static/agent-admin/` 与 `services/agent-service/.../static/agent-console/` 是早期联调页，保留用于兼容既有后端静态资源测试，不再作为用户入口。

[返回开发入口](repository-layout.md) · [返回文档目录](../README.md)
