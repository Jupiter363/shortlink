# JUPITER RELAY 产品链路真实 UAT

测试日期：2026-09-14。入口：`http://127.0.0.1:5174`，经真实 Admin API 和本地 APISIX。使用独立 Chromium CDP 会话与专用 `RelayUAT*` 账户，未改动 Jupiter 账户，未停用任何账户，未调用 Agent 或大模型。

## 结果

| 场景 | 实际结果 |
| --- | --- |
| 登录与刷新恢复 | 登录成功后经过账户初始化；刷新恢复已有 token 后仍查询初始化状态，并正常进入默认工作台。持久化内容不含密码字段。 |
| 分组 | 真实创建、重命名、上/下移排序、删除空分组均成功；包含短链的分组无法从界面删除。 |
| 单链 | 原始链接和描述更新后由后端列表确认；回收后离开活动列表，在回收站可见，恢复后返回原分组。 |
| 永久删除 | 仅删除一次性 UAT 短链；必须输入准确短码并勾选二次确认，成功后不再出现在回收站。 |
| 二维码 | 图片真实生成且可加载；目标采用配置的 `http://localhost:19080`。下载文件是 PNG，扩展名与 8 字节文件签名一致。 |
| 资料更新 | 修改姓名成功；手机输入留空时，不会把 `138****1234` 等脱敏值写回，原手机显示保持一致。 |
| 修改密码 | 错误当前密码被拒绝；正确改密后清除本机登录并退出；原 token 请求分组接口返回 HTTP 401；新密码重新登录、刷新恢复正常。新密码只保存在忽略的 UAT 凭据文件。 |
| 版本冲突 | 打开编辑框后，用同一专用账户真实更新该短链，再提交旧编辑框；服务端拒绝旧版本，界面明确提示冲突。点击刷新后显示实际并发更新的内容，经用户再次确认后成功保存。 |
| 到期时间 | 输入 `2026-09-17T23:00`；后端 API 返回 `2026-09-17 23:00:00`；列表显示 `2026-09-17 23:00`；重新打开编辑框为 `2026-09-17T23:00`，均为北京时间。 |
| 同步批量 | 12 行有效链接真实创建；12 条结果逐行展示，导出真实 XLSX。 |
| 列表分页 | 最初 12 条同步结果显示第一页 10 条；后续增加 2 条有效异步结果后，第一页 10 条、第二页 4 条，不重复；返回第一页仍正确。 |
| 异步批量 | 提交 501 行：2 行有效、499 行长度超限。输入文本 1,058,456 字节，超过 1 MiB、低于 8 MiB。真实 worker 返回 `PARTIAL_SUCCESS`，计数为总计 501、有效 2、无效 499、成功 2、失败 0。 |
| 游标结果 | 真实请求先加载 20 行，再追加 20 行；行号严格前进，无重复。 |
| 异步导出 | CSV 为真实后端文件，包含表头和全部 501 行结果，共 502 行、22,611 字节。 |
| 取消任务 | 另外提交 501 行全部无效的任务，发送取消请求后真实服务端返回 `CANCELLED`，未伪造取消状态。 |
| 窄屏 | 390px 账户页及批量抽屉均无页面横向溢出；正文、确认按钮与结果可读。 |

同步批量导出的 XLSX 使用 ZIP/XML 独立解析验证：包含 `xl/workbook.xml` 和工作表，工作表为 13 行，即 1 行表头、12 行实际数据。该文件不是更换扩展名的 CSV。二维码已验证图片加载、真实 MIME、文件签名和目标协议；当前环境未安装独立二维码解码器，没有把“成功渲染”报告为“独立扫码解码通过”。

后端日期输出修复部署后，完成了版本冲突与自定义到期时间的联合复验。既有短链创建时间 API 返回 `2026-09-14 21:18:56`，列表显示 `2026-09-14 21:18`。前端没有增加额外 8 小时的补偿逻辑。

## 本轮发现与修复

1. **写操作成功后复用旧统计快照。** 短链实际已创建，但刷新列表返回 `Statistics snapshot unavailable`。产品弹窗写后改为 `refreshLinks({ reset: true })`；同步批量创建后的真实列表已复验通过。没有将已确认成功的创建自动重发。
2. **创建时间分页复用上一页范围的快照。** 后端 `createTime` 按页选择授权短链，上一页快照不适用于下一页。根集成逻辑已修为：只有全组指标排序使用固定范围快照续页；创建时间分页重新获取当前页覆盖数据。第二页和返回第一页已复验通过。
3. **二维码图片和文件后缀不一致。** 真实前端二维码函数返回 PNG，原型曾返回 SVG。下载改为根据真实 data URL MIME 选择扩展名，已通过文件字节签名复验。

原始 JSON 保留初验中断和后续复验记录；历史失败未被删除。`defects` 字段标注对应修复和通过的复验场景。自动化脚本选择器自身的调试错误不计为产品缺陷。

## 源码与契约验证

产品新增/迁移文件：

- `frontend/console-vue/src/relay/views/AuthView.vue`
- `frontend/console-vue/src/relay/views/AccountView.vue`
- `frontend/console-vue/src/relay/views/ProductDialogs.vue`
- `frontend/console-vue/src/relay/views/product.css`
- `frontend/console-vue/src/relay/api/product.js`
- `frontend/console-vue/src/relay/domain/product-model.js`
- `frontend/console-vue/src/relay/domain/product-model.test.js`

3 个 Vue SFC 通过编译；产品文件的 ESLint 通过；11 项产品领域测试通过，覆盖真实 DTO 映射、缺失统计值、请求幂等标识、版本条件、域名协议限制、8 MiB UTF-8 序列化大小、500/501 行边界、结果游标、脱敏手机和密码提交契约。

所有产品界面均调用真实接口。没有保留原型账户 Map、假延迟、假任务状态推进、样例填充或改后缀伪造导出。

## 证据

- [原始 UAT 检查与复验记录](product-uat.json)
- [二维码](screenshots/product-qr-desktop.png)
- [账户桌面](screenshots/product-account-desktop.png)
- [账户 390px](screenshots/product-account-mobile.png)
- [同步批量结果](screenshots/product-batch-sync.png)
- [异步部分成功结果](screenshots/product-batch-async.png)
- [异步 390px](screenshots/product-batch-mobile.png)
- [真实取消终态](screenshots/product-batch-cancel.png)
- [分页修复复验](screenshots/product-page2-retest.png)
- [真实当前密码校验](screenshots/product-password-verification.png)
- [真实版本冲突](screenshots/product-version-conflict.png)
- [到期时间回显](screenshots/product-expiry-retest.png)

测试脚本和导出文件保存在忽略的 `.work/relay-uat/product-*.cjs` 与 `.work/relay-uat/downloads/`。专用 UAT 批量分组仅增加了 14 条有效链接；499 条无效输入以及取消任务未转换为大量真实短链。真实跳转冷请求、Agent/统计联调和后端日期输出由其他集成验收记录覆盖，本报告不替代这些结论。
