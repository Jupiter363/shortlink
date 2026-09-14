# 退出登录与分组失败增量 UAT

结果：**PASS**。入口为本地 5174，经实际管理接口；使用专用 UAT 会话，不退出 Jupiter、不修改密码、不调用 Agent。

| 检查项 | 结果 | 证据类型 |
| --- | --- | --- |
| Dedicated account logs in through the real initialization path | PASS | REAL_HTTP |
| Account logout click sends an actual DELETE request | PASS | REAL_HTTP |
| Logout URL contains neither query parameters nor token | PASS | REAL_HTTP |
| Actual logout endpoint responds HTTP 200 | PASS | REAL_HTTP |
| Logout routes to login and removes locally stored session | PASS | REAL_UI |
| The original token is rejected by the group API | PASS | REAL_HTTP |
| Re-login remains usable after real server logout | PASS | REAL_HTTP |
| Source group genuinely loads its count and coverage before injection | PASS | REAL_HTTP |
| Injected target-group failure displays unknown count instead of old count | PASS | FAULT_INJECTION |
| Injected target-group failure clears the old coverage disclosure and rows | PASS | FAULT_INJECTION |
| CDP injection was applied to exactly the intended next page read | PASS | FAULT_INJECTION |
| 390px failure view stays within the viewport | PASS | FAULT_INJECTION |
| Removing the injection and clicking refresh restores the target count | PASS | REAL_HTTP |
| The recovery page response is a real HTTP 200 | PASS | REAL_HTTP |
| No uncaught application exceptions occurred | PASS | BROWSER_RUNTIME |

## 真实退出请求

账户页点击退出，检查 DELETE /api/short-link/admin/v1/user/logout 的 URL 无 query、无 token，真实返回 HTTP 200，页面返回 /login。随后只读请求验证原 token 访问分组返回 401，再次表单登录并确认分组可用。报告不保存密码、token 或请求认证头。

## 故障注入边界

分组失败部分使用隔离浏览器 CDP Fetch 注入下一次 /page 响应为 HTTP 503；这不是实际后端故障。先真实加载来源分组，再切换目标分组验证计数为「—」、旧统计覆盖提示及旧行消失。撤销注入后，点击真实刷新按钮，以后端 HTTP 200 与真实目标数量验证恢复。

只运行这两项增量回归，视口为 1280px 和 390px。测试浏览器在 finally 中关闭，未运行全套烟测。

人工查看首轮 390px 截图时发现：分组切换按钮会裁切分组名前缀及数量后缀。该问题已由主线调整移动端 CSS，并通过下方 390px 专项复验。保留首轮截图用于对比；最终截图使用 `logout-scope-layout-*` 文件。本验收任务没有修改共享业务样式。

## 截图

- [logout-scope-logout-1280.png](screenshots/logout-scope-logout-1280.png)
- [logout-scope-source-1280.png](screenshots/logout-scope-source-1280.png)
- [logout-scope-failure-1280.png](screenshots/logout-scope-failure-1280.png)
- [logout-scope-failure-390.png](screenshots/logout-scope-failure-390.png)
- [logout-scope-recovered-390.png](screenshots/logout-scope-recovered-390.png)
- [logout-scope-recovered-1280.png](screenshots/logout-scope-recovered-1280.png)

[原始检查与注入记录](logout-scope-uat.json)


## 390px 布局增量复验

结果：**PASS**。只重验移动端切组失败与真实恢复，不重复退出登录，不修改密码。分组通过移动端可见的分组管理抽屉切换。范围按钮改为独占一行后，故障态与恢复态的完整文字均通过 Range 边界和 clientWidth/scrollWidth 检查；真实请求与故障注入标记保留于 JSON 的 visualRetest。浏览器已关闭。

- [logout-scope-layout-failure-390.png](screenshots/logout-scope-layout-failure-390.png)
- [logout-scope-layout-recovered-390.png](screenshots/logout-scope-layout-recovered-390.png)
