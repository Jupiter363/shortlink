# 木星中继站 · Taste 布局审查与优化

2026-09-15，关联 [Issue #44](https://github.com/Jupiter363/shortlink/issues/44)。审查基线为 `5ce62e9b34929b138f4c26c0accc783e38a9cc72`，实现位于 `frontend/console-vue/src/relay/`。浏览器验收访问本机 `http://127.0.0.1:5174`，使用实际构建、现有 Jupiter 会话和服务端数据。

## 设计判断

延续明亮的木星插画、黄色选中态、蓝色主操作及现有 Vue / R 组件体系。工作台以完成管理任务为主：页面唯一标题和主操作留在顶栏；正文从上方开始呈现功能；桌面分区利用可用高度，手机保留清楚的导航文字及合理滚动。没有引入新的设计系统或回答协议。

先用 design-taste-frontend 的现状审查、层级、间距和响应式检查方法发现问题，再调整代码并回到内置浏览器验证。登录页保留品牌表现力，数据工作区按实际内容决定密度。

## 发现与修复

| 问题及触发条件 | 修复后的行为 | 对照证据 |
| --- | --- | --- |
| 初次登录或直接刷新时，标题 Teleport 目标尚未挂载，顶栏标题和创建等操作丢失；切换页面后才出现 | 延后同一挂载周期内的 Teleport 目标解析，直接进入页面即有唯一标题与主操作 | [登录后原状](before/workspace-after-login.jpg)、[修复后](after/workspace-1440x900.jpg) |
| 手机固定图标侧栏占用有限横向空间，两个 Agent 等入口缺少完整文字 | 600px 及以下使用顶栏导航按钮和完整文字菜单；沿用弹窗焦点管理、Escape 关闭及请求期间的导航限制 | [原工作区](before/workspace-390.jpg)、[现工作区](after/workspace-390x844.jpg)、[导航菜单](after/mobile-navigation-320.jpg) |
| 顶栏占用较多高度；统计、账户与 Agent 在桌面垂直居中，首屏上方留白大 | 桌面顶栏 76px，正文采用紧凑上边距；统计与账户顶部从约 182/279px 调整到约 103/104px，Agent 结果利用剩余高度 | [原统计](before/analytics-1440.jpg)、[现统计](after/analytics-1440x900.jpg)、[原账户](before/account-1440.jpg)、[现账户](after/account-1440x900.jpg) |
| 创建/编辑表单的高优先级选择器覆盖窄屏单列规则 | 手机分组与有效期按单列排列，自定义到期时间和底部操作可达；长标题允许换行 | [原创建](before/create-390.jpg)、[现创建](after/create-custom-date-320.jpg)、[现编辑](after/edit-390.jpg) |
| 分组长名称挤压数量和操作；短列表容器额外占满高度，批量提示可能被推远 | 分组名称、数量、管理操作分区排列；列表容器按内容布局，工作区仍填满可用空间，记录保持稳定行高 | [分组面板](after/groups-320.jpg)、[四条记录](after/workspace-four-rows-1440.jpg) |
| 矮屏批量输入区初始高度过大，操作较远 | 缩小初始输入高度，保留输入框调整与内部滚动 | [1280×600](after/batch-1280x600.jpg)、[320×740](after/batch-320.jpg) |
| 统计筛选控件未对齐；样式 class 落到 select 而非字段容器，响应式布局与桌面隐藏标签失效 | 为范围/维度选择增加布局容器；日期、范围及应用操作对齐为 48px；窄屏按列重排 | [原筛选](before/analytics-filters-1440.jpg)、[现筛选](after/analytics-filters-1440.jpg)、[手机维度](after/analytics-dimensions-320.jpg) |
| 访问记录抽屉传入无单位字符串宽度，桌面意外铺满；表格固定最小宽度造成横向滚动 | 使用数值 760px 宽度，五列长文本换行；手机继续使用记录卡片，分页栏可达 | [修复前抽屉](before/access-records-fullwidth-1440.jpg)、[现桌面抽屉](after/access-records-1440.jpg)、[手机底部](after/access-records-320-bottom.jpg) |
| Agent 说明与输入区争抢高度，手机示例入口不可用；风险指标及长数字在窄屏拥挤 | 输入区按内容决定高度，结果单独滚动；手机可展开示例且点击仅填写问题；风险指标两列、名称与值分行，修复趋势图多出的 1px 纵向滚动 | [原 Agent](before/agent-campaign-320.jpg)、[现 Agent](after/agent-campaign-320x740.jpg)、[原风险](before/risk-320.jpg)、[现风险](after/risk-320x740.jpg) |
| 手机账户详情横向密集，安全管理折叠入口不明确 | 小屏详情单列，安全管理显示展开状态与操作提示 | [账户页](after/account-320x740.jpg)、[安全管理展开](after/account-security-320.jpg) |
| 登录页固定最小高度使 1280×600 登录操作贴近/超出底部；320px 页面出现 15px 横向溢出 | 登录品牌区适配矮屏，登录按钮底部从约 601px 调整到 489px；取消 body 固定最小宽度，滚动条不再撑宽页面；注册保留自然纵向滚动 | [原矮屏登录](before/login-1280x600.jpg)、[现矮屏登录](after/login-1280x600.jpg)、[320px 登录](after/login-320x740.jpg)、[注册底部](after/register-320x740-bottom.jpg) |
| 桌面收起侧栏后缩到手机、打开导航，再扩大窗口，紧凑侧栏选择器误伤弹窗项文字 | 紧凑样式限定于侧栏，弹窗保留七个完整文字入口 | [紧凑侧栏](after/sidebar-compact-1440.jpg)、[放大后的导航弹窗](after/mobile-navigation-resize-1440.jpg) |

## 页面与尺寸矩阵

以下 7 个工作台页面分别检查 **1440×900、1280×720、1280×600、390×844、320×740**，共 35 个页面/尺寸组合。几何记录全部满足：页面无横向溢出、只有一个 h1 且位于顶栏；顶栏主操作在视口范围内。长内容、矮屏保留纵向滚动，不以裁切来满足首屏高度。

| 页面 | 桌面截图 | 手机截图 |
| --- | --- | --- |
| 短链接 | [1440×900](after/workspace-1440x900.jpg) | [320×740](after/workspace-320x740.jpg) |
| 访问统计 | [1440×900](after/analytics-1440x900.jpg) | [320×740](after/analytics-320x740.jpg) |
| 风险中心 | [1440×900](after/risk-1440x900.jpg) | [320×740](after/risk-320x740.jpg) |
| 投放分析 Agent | [1440×900](after/agent-campaign-1440x900.jpg) | [320×740](after/agent-campaign-320x740.jpg) |
| 安全风控 Agent | [1440×900](after/agent-security-1440x900.jpg) | [320×740](after/agent-security-320x740.jpg) |
| 回收站 | [1440×900](after/recycle-1440x900.jpg) | [320×740](after/recycle-320x740.jpg) |
| 账户中心 | [1440×900](after/account-1440x900.jpg) | [320×740](after/account-320x740.jpg) |

登录、注册另按相同五档尺寸检查，共 10 个组合。320px 下页面实际内容宽度 305px（另有 15px 纵向滚动条），scrollWidth 同为 305px；注册按钮可滚动到视口内。所有矩阵截图以 `页面-宽x高.jpg` 命名，其余截图记录独立交互状态。

原始尺寸与滚动几何见 [修复前记录](before/geometry.json)、[修复后记录](after/geometry.json)、[登录/注册记录](after/auth-geometry.json)。截图保留浏览器原生 JPEG；部分带滚动条的登录/注册截图由浏览器等比缩小，CSS 视口尺寸以几何记录为准。截图记录对应采集时点，不代表以后数据或服务状态不变。

## 交互与真实长内容

- 初次登录及直接刷新后标题和主操作可见；已验证手机菜单导航、Escape 关闭和焦点回到“打开导航”。桌面收起侧栏后跨手机/桌面尺寸的弹窗文字保持完整。
- 手机创建、自定义有效期、编辑与取消、分组管理、账户编辑与安全说明展开可用。没有保存这些测试表单。2 条和 4 条真实短链保持约 114px 的桌面行高，未按少量数据拉伸记录。
- 统计筛选应用、趋势图/表切换、手机运营商维度选择及访问记录读取可用。760px 抽屉内表格宽度与 scrollWidth 均约 695px；手机 13 条访问记录容器可从顶部滚到分页栏（scrollTop 约 4615px）。
- 两类 Agent 示例点击均仅填入文本并聚焦输入，不自动发起请求。实际执行了一次投放分析只读请求，返回 2 项已完成工具记录和 6 个 Graph 节点；切换回答、证据及执行轨迹可用。
- 桌面长回答结果区可见高度约 707px、内容约 1733px，使用内部滚动。手机工作区可见高度约 587px、内容约 3356px，滚动到底后最后内容下边缘约 716px，在 740px 视口内。见 [桌面回答](after/agent-answer-1440.jpg)、[手机回答](after/agent-answer-320.jpg)、[手机最后一段](after/agent-answer-320-bottom.jpg)、[证据](after/agent-evidence-320.jpg)、[工具及 Graph](after/agent-trace-1440.jpg)。

## 检查结果与边界

- `npm.cmd run test`：73 项通过，0 失败。
- `npm.cmd run lint`：通过。
- `npm.cmd run build`：通过，105 模块；产物已更新本地前端容器所挂载的 dist，无需重启服务。
- 三条并行审查分别检查工作区/公共外壳、统计/账户、Agent/风险；主代理集成并进行浏览器几何、交互和截图复验。

本轮是布局与交互可达性验收：未提交创建、编辑、账户或风控写操作，未重新验收策略传播，没有压测或中间件重启。PARTIAL、UNKNOWN、地域/ISP 缺口及未知累计指标按真实服务响应保留；没有把本轮分析回答当成完整数据证明。Agent 仍沿用已有纯文本回答展示，未更改 schema。未穷举全部证据展开态、极限长度输入或所有操作系统原生日期选择器。

[返回管理前端说明](../../development/frontend-console.md)
