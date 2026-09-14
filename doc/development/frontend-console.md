# 管理前端与 Agent 工作台

`frontend/console-vue/` 是标准管理入口。木星中继站的新主题由 `src/main.js` 启动 `src/relay/App.vue`，使用同一套 Vue/Vite 构建；设计原型在 `doc/design/relay-preview` 归档。

## 请求与认证

```text
Browser -> Nginx /api -> APISIX (management host) -> Admin
                                                -> Command / Analytics API / Agent Service
```

新的 `src/relay/api/http.js` 使用同源 Fetch，请求头是 `Username` 与 `Token`。它保留超出 JavaScript 安全整数范围的 ID、解包业务结果、处理 401 会话过期、取消与超时，并对流式文件下载单独处理。迟到的旧请求不能使新登录退出。

登录、注册后均查询真实初始化状态。只有 READY 且默认分组可见时进入工作台；刷新浏览器恢复 token 也需要重新核验。未就绪、初始化失败和状态不可确认有独立界面。退出登录清空用户数据和内存中的 Agent 会话、批量任务、风险命令。

退出使用不含参数的 `DELETE /api/short-link/admin/v1/user/logout`，凭据仅放在既有认证头；Admin 从入站已验证的会话取得退出对象，避免 token 进入 URL 和代理错误日志。旧客户端若仍传 query，必须提供与已验证会话完全匹配的一对参数。先部署兼容的 Admin，再部署新前端。

## 页面与行为

| 页面 | 路由 |
| --- | --- |
| 登录 / 注册 | `/login` / `/register` |
| 短链管理 / 回收站 | `/home/space` / `/home/recycleBin` |
| 访问统计 / 风险中心 | `/home/analytics` / `/home/risk-center` |
| 投放分析 Agent | `/home/agent/campaign-analysis` |
| 安全风控 Agent | `/home/agent/security-risk` |
| 账户中心 | `/home/account` |

短链管理使用服务端分组、分页和排序。创建请求保留幂等 requestId；修改、回收和恢复回传实际 routeVersion，不伪造默认版本。2–500 行批量请求同步返回，501–50,000 行返回异步任务，前端继续轮询并以行游标查询结果。8 MiB 限制按实际 UTF-8 请求体计算，结果导出来自后端，未知创建结果保留原请求进行核实。

统计最多查询 7 天和 500 条授权短链。累计覆盖未知、采集部分完整、近似 UV/UIP、地域未知、运营商解析和保留历史范围均保留服务端口径。访问记录后续页固定 snapshotId 与 cursor，失效后重新读取，不能把不同快照拼接。未返回的数字显示「—」，不填充演示值。

两个 Agent 只提交 sessionId、agentType、message，服务端决定可信主体与工具权限。同一登录期间两类会话独立保存在内存，页面切换后可以回看；请求运行期间锁定导航，超时或离开后的旧结果不能串入其他会话。页面展示真实回答、证据、工具记录和 Graph 轨迹；健康入口仅表示 HTTP 可达。

风险中心分别展示人工审核、当前策略和停用命令状态。UNKNOWN 不视为成功；结果未知时继续查询原 commandId，不重复提交停用动作。COMMITTED 与传播确认分别展示。策略总数或覆盖未提供时保留未知。

已授权分组的风险聚合画像尚未生成时，概览返回 `profileStatus=NOT_EVALUATED`，画像计数和分数为 `null`、等级为 `UNKNOWN`；界面显示“尚未生成风险画像”和「—」。已有画像为 `READY`。人工审核、单链证据与当前策略各自保留已经查询到的事实，不受聚合画像是否生成影响。数据库异常与权限错误仍按错误处理。

## 构建与部署

```sh
cd frontend/console-vue
npm ci
npm run test
npm run lint
npm run build
```

Windows 必要时使用 `npm.cmd`。Vite 开发代理转发 `/api` 至 `127.0.0.1:19080` 并设置管理 Host `admin.local.test`。生产构建将 `dist/` 挂载给 Nginx，配置见 [Nginx 示例](../../frontend/console-vue/deploy/nginx.conf) 与 [部署说明](../../frontend/console-vue/deploy/README.md)。Nginx 处理 SPA fallback、静态资源缓存、8 MiB 外层请求上限和 Agent 专用等待预算；请求日志不记录 query 参数，避免旧 logout 接口的 token 落入访问日志。

创建不在前端指定域名，仍由 Command 统一分配。本地历史地址没有协议且 APISIX 端口只提供 HTTP 时，可以在忽略的 `.env.local` 配置 `VITE_SHORTLINK_PUBLIC_ORIGIN=http://localhost:19080`；仅对 host 完全相同的地址应用协议。生产构建使用实际 HTTPS origin，不得携带本地 override 或任何密钥。

访问记录和风险卡片也复用上述规则显示短链地址。协议调整仅用于展示或访问，后端返回的 canonical `fullShortUrl` 与授权请求参数保持原值。

2026-09-14 的真实联调证据归档在 [Relay 联调目录](../integration/relay-console-2026-09-14/)。原型的模拟交互检查不能代替该验收。

2026-09-15 的 [Taste 布局审查与验收](../integration/taste-layout-2026-09-15/README.md) 记录登录/注册及七个工作台页面的五档尺寸检查、手机导航、表单/抽屉、真实长回答与前后对照。此次修复了直接登录/刷新时顶栏标题和操作缺失、窄屏宽度不足及筛选控件错位等问题；统计缺口和风控写操作的既有验收边界保持不变。

[返回开发入口](repository-layout.md) · [返回文档目录](../README.md)
