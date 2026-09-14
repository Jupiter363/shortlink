# 木星中继站 · JUPITER RELAY

`frontend/console-vue` 是唯一管理前端构建入口，新主题实现位于 `src/relay/`，由现有 `src/main.js` 启动。`doc/design/relay-preview` 仅为设计归档，不能用于业务验收。

```sh
npm ci
npm run dev
npm run test
npm run lint
npm run build
```

Windows PowerShell 若将 `npm` 解析为系统目录内的无扩展文件，使用 `npm.cmd`。构建输出仍为 `dist/`，现有 Docker 前端通过只读挂载提供静态文件。

开发代理和 Nginx 都将同源 `/api` 转发至 APISIX 管理入口；真实业务链路为 Browser → APISIX → Admin → Command / Analytics / Agent。浏览器仅使用当前账户的 `Username` / `Token`，不接收内部服务密钥或模型密钥。

| 页面 | 路径 |
| --- | --- |
| 登录、注册 | `/login`、`/register` |
| 短链工作区、回收站 | `/home/space`、`/home/recycleBin` |
| 统计、风险中心 | `/home/analytics`、`/home/risk-center` |
| 投放分析、安全风控 Agent | `/home/agent/campaign-analysis`、`/home/agent/security-risk` |
| 账户中心 | `/home/account` |

登录后查询实际初始化状态，只有 READY 且默认分组可见时才进入工作台。每次恢复会话均重新检查初始化；退出登录清除用户数据、任务和 Agent 会话。密码不保存在浏览器中。

批量创建支持 2–500 行同步结果、501–50,000 行异步任务和 8 MiB 请求体限制；任务结果通过后端游标查询，导出直接下载后端文件。未知结果保留原请求标识。复制和二维码以服务器分配的地址为基础，二维码在浏览器本地生成。

本地跳转入口使用 HTTP 时，在被忽略的 `.env.local` 中明确设置：

```dotenv
VITE_SHORTLINK_PUBLIC_ORIGIN=http://localhost:19080
```

此构建变量只改变 **host 完全匹配** 的短链协议，不改写其他域名，也不改变创建请求的域名分配。生产部署应设置自己的 HTTPS origin 或省略该变量，禁止将本机 override 带入生产构建。全部 `VITE_` 变量均为公开配置，不得放置凭据。

生产 Nginx 示例及超时说明见 [deploy/README.md](deploy/README.md)。统计窗口和完整性、风险命令确认、真实验收记录见 [前端开发指南](../../doc/development/frontend-console.md)。
