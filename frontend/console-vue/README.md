# ShortLink 管理控制台

这是 ShortLink 的标准管理前端，包含账号登录、短链接与分组管理、访问统计、回收站及 Agent 工作台。

## 本地开发

先启动后端与 APISIX，再执行：

```bash
npm ci
npm run dev
```

开发服务器默认把 `/api` 请求代理到 `http://127.0.0.1:19080`，并使用管理面 Host `admin.local.test` 进入 APISIX。浏览器始终只访问前端同源的 `/api`，不直接调用 Admin 或 Agent 服务。

## 生产构建

```bash
npm ci
npm run build
```

构建产物位于 `dist/`。部署时需要让静态服务器把未知页面路由回退到 `index.html`，并把 `/api/` 反向代理到 APISIX 管理入口。

Agent 工作台路径为 `/home/agent`。它复用当前登录产生的 `Token` 和 `Username` 请求头，正式调用链为：

```text
Vue Console -> /api -> APISIX -> Admin -> Agent Service
```
