# ShortLink 前端重构：Figma AI 低调用生成提示词

> 目标：用一次主生成尽可能完成设计系统、组件库、9 个一级页面和核心交互，再用一次审计补全修复遗漏。
> 业务基线：[页面与组件盘点](01-页面与组件盘点.md)
> 对应 Issue：[#27](https://github.com/Jupiter363/shortlink/issues/27)

## 1. 使用方式

1. 在一个新的或专用于本次重构的 Figma 文件中运行“第一次调用：主生成提示词”。
2. 等待第一次生成全部结束，不在中途逐页追加要求。
3. 在同一个文件、保留第一次生成上下文的情况下运行“第二次调用：审计补全提示词”。
4. 第二次调用只修复和补齐现有结果，不允许重新建立另一套组件、色彩或页面。

两次调用的优先级都是：**业务正确 > 组件复用 > 信息层级 > 响应式 > 装饰细节**。如果单次生成容量不足，优先保住 Foundations、共享 Components、9 个桌面端默认页面和关键业务浮层，再由第二次调用补齐状态与窄屏画板。

## 2. 第一次调用：主生成提示词

复制以下完整内容作为一次提示词：

```text
你是一名负责企业级数据产品的资深产品设计师和 Figma Design System 架构师。请直接在当前 Figma 文件中一次性创建 ShortLink 新版中文管理控制台。不要先解释、不要只输出计划、不要只生成一个示例页面，也不要询问我逐页确认；直接构建可编辑、可复用、可继续迭代的完整设计。

这是从零重做的新前端。不要继承旧前端视觉，只保留下面给出的真实业务、权限、路由兼容和数据语义。产品是一套面向短链接运营、数据分析与安全风控人员的高密度生产工作台，设计方向命名为 “Signal Workbench / 信号工作台”。它要前卫、鲜亮、精密、有实时系统感，同时保持企业后台的可读性、可访问性和长时间使用舒适度。前卫感来自编辑化排版、非对称工作区、清晰的数据节奏、丰富但受控的颜色与状态反馈，不依赖暗色霓虹、满屏渐变或玻璃拟态。设计参数：DESIGN_VARIANCE=7，MOTION_INTENSITY=4，VISUAL_DENSITY=8。

一、一次性建立文件结构

只创建以下 4 个 Figma Pages，内部按 Sections 管理，减少跨 Page 重复和生成负担。如果当前 AI 能力不能创建多个 Pages，就在当前 Page 创建同名顶层 Sections，并继续完成全部内容，不能省略业务页面：

1. 00_README_CONTRACT
   - Product_Map
   - Route_and_Flow_Index
   - Business_Contracts
   - Do_Not_Build
2. 01_DESIGN_SYSTEM
   - Foundations
   - AppShell
   - Base_Controls
   - Shortlink_Components
   - Analytics_Components
   - Agent_Components
   - Risk_Components
   - Auth_Account_Components
   - State_Variants
3. 02_PRODUCT_SCREENS
   - 01_P01_Login
   - 02_P02_Register
   - 03_P03_Shortlink_Workspace（Batch Job Drawer 归入此 Section）
   - 04_P04_Recycle_Bin
   - 05_P05_Analytics
   - 06_P06_Campaign_Agent
   - 07_P07_Risk_Center
   - 08_P08_Security_Agent
   - 09_P09_Account
   - 10_Prototype_Flows（直接复用本 Page 的 P01–P09 页面帧建立连接）
4. 03_RESPONSIVE_PROTOTYPE_QA
   - Responsive_Patterns
   - State_Matrix
   - Prototype_Index
   - Coverage_Matrix
   - Final_QA

所有页面必须引用同一套 Variables、Styles 和 Components。禁止在不同页面复制出外观相似但互不关联的按钮、输入框、表格、导航或卡片。使用可机器审计的命名：页面帧采用 `P03/ShortlinkWorkspace/Default/1440`，流程帧采用 `F04/Step03/AsyncRunning`，组件采用 `Data/Table/Row`，Variant 采用 `State=Default|Hover|Selected|Disabled|Loading`。

二、先建立 Foundations 和 Variables

建立 Light Theme，并预留语义完整的变量结构，不需要生成另一套 Dark Theme。Variables 分成两层：Primitive 保存原始颜色、间距、尺寸和圆角；Semantic 保存 Canvas、Surface、Text、Border、Action、Status、Chart 等用途，页面只绑定 Semantic Variables。

颜色建议：
- Canvas：#F5F7FC，保持冷白、明亮，不使用大面积灰暗底色。
- Surface：#FFFFFF；Subtle Surface：#EEF3FF；Raised Surface：#FAFBFF。
- Primary Text：#0B1736；Secondary Text：#53627D；Border：#D8E2F2。
- 唯一全局交互主色：Electric Cobalt #3157F6，按钮、链接、焦点和选中状态保持一致。
- Analytics 数据线/前景色：Cyan #087A8F、Blue #3157C8、Violet #6847C6、Emerald #0B7652；较浅的图表填充只能配合深色描边、数据标记或纹理，不能单独承担区分。
- Status/Success：Foreground #086B47、Background #EAF8F1、Border #64B893。
- Status/Warning：Foreground #774000、Background #FFF4D6、Border #D99A2B。
- Status/Danger：Foreground #A8293E、Background #FFF0F3、Border #DE7789。
- Status/Info：Foreground #1D55B3、Background #EDF4FF、Border #7BA5E8。
- 模块可以使用上述颜色的浅色背景和细边框区分，但模块色不能替代全局按钮和链接色。

字体：中文优先 MiSans Variable，缺失时使用 Noto Sans SC；URL、短码、jobId、commandId、QPS、时间戳使用 Geist Mono。建立确定的 Text Styles：Display 36/44 700；Page Title 28/36 700；Section Title 20/28 600；Body 14/22 400；Caption 12/18 400；Label 13/20 600；Mono 13/20 500。正文不小于 14px，Caption 只用于辅助元数据。

使用 4px 基础单位和 8px 主节奏，Spacing Variables 为 4/8/12/16/20/24/32/40/48/64。主工作区内边距 20–24px，紧凑控件内边距至少 12–16px，桌面表格常规行高 48–52px。面板圆角统一 12px，输入框 10px，仅 Badge、Chip 和状态点可使用胶囊形。阴影建立统一 Effect Styles，保持克制，只表达浮层或真实层级。图标与容器顶部至少 16px，图标与标题间距 10–12px，任何按钮文字不得折成两行。

建立 1440px 的 12 列桌面栅格；侧栏 232px 展开、72px 收起，顶栏约 64px。定义 1280px、768px、390px 断点与 reduced motion。控件反馈 140–180ms，Drawer/视图切换 220–280ms；动画只用于加载、层级切换、状态变化和操作反馈。

三、一次性建立共享 Component Library

为所有交互组件创建 Auto Layout、Component Properties 和 Variants。交互组件至少有 default、hover、focus、active、disabled、loading；状态不能只靠颜色表达。

1. 应用壳：AppShell、展开/收起 Sidebar、四个主导航项、Topbar、PageHeader、Breadcrumb、ContextBar、UserMenu、HelpMenu、全局反馈层。
2. 基础操作：Primary/Secondary/Text/Danger Button、IconButton、TextField、PasswordField、TextArea、Select、Checkbox、DateRange、DateTime、SegmentedControl、SortControl、FormField、字符/行数计数。
3. 反馈与浮层：Toast、InlineAlert、Tooltip、Popover、Dialog、Drawer、DestructiveConfirm、UnsavedConfirm、Skeleton、Empty、Error、Permission、429、Timeout、AsyncJobStatus、DataFreshness、CompletenessBanner。
4. 短链领域：GroupRail、GroupItem、GroupMenu、LinkTable、LinkIdentityCell、MetricCell、LinkStatus、ValidityCell、RowActions、Pagination、CreateLinkForm、BatchEditor、BatchJobDrawer、BatchResultRow、EditLinkForm、QRPreview、CopyFeedback、VersionConflict、RecycleActions。
5. 统计领域：ScopeSummary、MetricStrip、TrendChart、ChartTableToggle、GeoMap、DimensionRanking、HourDistribution、WeekdayDistribution、HighFrequencyIpTable、AccessRecordTable、SnapshotCursorPager、MetricDefinition、UnknownDimension。
6. Agent 领域：AgentHealth、SessionHeader、ScopeToMessageControl、PromptComposer、PresetPrompt、RunningPlaceholder、AnswerPanel、InsightCard、WarningStack、EvidenceBlock、DataSourceMeta、CompletedToolTimeline、GraphNode、TraceSummary、AgentAccessRecords、DebugDisclosure、AgentErrorRetry。
7. 风险领域：RiskLevel、RiskSignal、RiskGroupSummary、RiskLinkCard、RiskLinkDetail、ReasonCode、RiskEventRow、EvidencePanel、ReviewDialog、CurrentPolicyPanel、DisablePolicyConfirm、CommandPropagationStatus、AutoLimitResult。
8. 认证与账户：AuthShell、LoginForm、RegisterForm、PasswordRequirement、InitializationGate、ProfileSummary、ProfileEditForm、CurrentPasswordConfirm、DisableAccountConfirm、SessionExpired。

功能图标统一使用 Phosphor，保持相同笔画和视觉尺寸，禁止混用多套图标或使用 Emoji。只有真正出现第三方品牌时才使用官方 Logo；缺少官方资产时保留清晰命名的 Asset Slot，禁止臆造品牌图标。“图标距容器顶部至少 16px”只适用于以图标为主的信息卡和信息块，不适用于按钮、表格单元格或行内图标。

四、一次性生成 9 个一级页面模板

P01 登录页，路由 /login：
- 使用前卫但克制的双区或非对称布局，一侧表达实时信号与短链数据语义，另一侧是清晰登录表单。
- 字段为 username、8–15 位 password；支持密码显隐、校验、提交中、用户名或密码错误、重复/异地登录、账号状态变化、服务失败。
- 保留“记住登录”复选框及选中/未选中状态；它只决定是否持久保存 token 和 username，禁止写成“记住密码”，也不保存明文密码。
- 登录成功支持 redirect 回跳；提供前往注册页入口。
- 当前没有验证码 challenge、找回密码、邮箱验证、MFA 或管理员登录合同，不生成这些入口。

P02 注册页，建议路由 /register，并兼容 /login 内切换：
- 字段为 username、password、realName、phone、mail；包含用户名占用检查与 8–15 位密码要求。
- 生成默认、校验失败、用户名已存在、提交中、注册成功状态。
- 注册接口不返回登录态。注册成功后先使用刚提交的凭据登录或引导用户登录，取得 token 后才能查询账户初始化状态；不能从注册响应直接进入受保护的初始化接口。

P03 短链接工作区，路由 /home/space：
- 使用“分组导航 + 高密度数据工作区 + 上下文详情 Drawer”的结构。
- 主导航包含短链接、风险中心、投放分析 Agent、安全风控 Agent；回收站属于短链接域，账户中心位于用户菜单。
- GroupRail 支持新建、重命名、删除和拖拽排序；每个用户最多 20 个分组，一次排序最多 20 个。初始化接口返回的 groupId 在 UI 中作为默认分组保护依据，但不要声称后端存在不可变 isDefault 标记。分组含短链或活动 job 时展示不可删除错误。
- 短链列表只使用后端支持的 gid、current、size、orderTag。允许按创建时间、今日/累计 PV、UV、UIP 等已支持 orderTag 排序。不要加入关键词搜索、任意维度筛选、批量编辑、批量移动或批量回收。
- 表格展示 favicon、描述、创建时间、有效期/失效状态、短链、原始 URL、今日/累计 PV/UV/UIP、二维码、复制、统计、编辑、移入回收站。
- 单链创建字段：原始 URL、自动获取标题/手工描述、分组、永久/自定义有效期。domain 不允许任意填写，服务端返回后再展示完整短链。
- 批量创建支持 2–50,000 行，链接与描述逐行对应，同时受序列化请求体不超过 8 MiB 的硬限制。2–500 行显示同步结果；501–50,000 行返回异步 jobId。生成可承载大文本粘贴的 BatchEditor、行数和估算字节双重校验、超出 8 MiB 的阻止提交状态和任务 Drawer；明确当前没有浏览器文件上传替代流程，不沿用旧前端 100 行上限。
- 同步结果按 requestId 提供 XLSX 导出；异步 job 展示 state、totalRows、validRows、invalidRows、succeededRows、failedRows、error，状态包含 VALIDATING、READY、RUNNING、SUCCEEDED、PARTIAL_SUCCESS、FAILED、CANCELLED，分别把 VALIDATING 表达为校验中、READY 表达为等待执行、RUNNING 表达为创建中。结果行使用 after + limit 游标加载并允许取消；只有 SUCCEEDED、PARTIAL_SUCCESS、FAILED、CANCELLED 终态显示 CSV 导出。429 时显示“导出繁忙，请稍后重试”。不要生成 job 历史列表、任意页码跳转、失败行重跑或 job 重试。
- 编辑、移入回收站、恢复和永久删除都必须携带 routeVersion 对应的 expectedVersion；冲突后显示刷新资源再试，不静默覆盖。

P04 回收站，路由 /home/recycleBin：
- 使用与短链工作区一致的表格语言，展示响应中真实存在的创建时间、有效期、原链接信息、状态、恢复和永久删除。当前响应没有 recycleTime/delTime，不能虚构“回收时间”。
- 永久删除明确说明跳转失效、停止统计、不可逆；包含二次确认、提交中、成功、失败、版本冲突和空态。

P05 统计详情工作区，从短链或分组进入：
- 设计完整页面模板，同时给出桌面上下文 Drawer/全屏承载方式，不自行决定最终新增路由。
- 支持单条短链或分组范围、最多 7 天的日期范围、访问数据和访问记录两个主视图。DateRange 禁止选择超过 7 天；分组授权范围最多 500 条短链，超过时显示 TOO_LARGE 和“分组超过 500 条短链，请缩小范围”。浏览器当前没有可用的历史统计 job 页面，不生成“转入后台长查询”入口。
- PV=访问次数，UV=访问人数，UIP=独立 IP 数，三者不能混用。使用 MetricStrip、当前时间窗趋势、图/表切换，不生成同比/环比，因为当前接口没有正式对比时间窗合同。
- 覆盖国家/省份地域、24 小时、一周、高频 IP、OS、浏览器、设备、运营商/ISP、新老访客。
- 运营商/ISP 是 IP 的异步离线归属，不能声称识别 Wi-Fi、4G、5G。
- 新老访客按所选短链或分组的历史首次观测判断，并披露历史保留范围；不能把新 Cookie 直接称为真实新用户。
- 访问记录展示北京时间、脱敏 IP/访客标识、地区、设备、浏览器、系统、ISP、历史首次观测、响应结果、事件类型；使用 snapshotId + cursor 的前后翻页，不设计任意页码跳转或无接口支撑的维度筛选。
- completeness 不是 COMPLETE 时显示“异步汇总中，当前快照可能不完整”；unknown 单独呈现，不能显示成 0 或并入真实分类。包含真正无数据、PARTIAL、unknown、历史覆盖不足、无法定位地域、游标失效和加载失败。

P06 投放分析 Agent，路由 /home/agent/campaign-analysis：
- 设计成可审计的生产任务工作台，不要设计成 ChatGPT 克隆。
- 包含独立 Session、新会话、Agent HTTP 健康状态、分析范围辅助、2000 字 Prompt Composer、快捷指令、RUNNING、一次性回答、洞察、警告、证据、数据来源、统计口径、更新时间、完成后的 Tool Timeline、Graph Trace 和折叠调试数据。
- 当前 chat 是同步 POST。RUNNING 期间只显示通用运行占位，不生成虚假流式文字或实时 Graph 节点；Tool/Graph 轨迹只在响应完成后展示。
- agentType 固定为 campaign-analysis。Scope 控件只把选择结果编译为 message 文本；请求 body 仍只有 sessionId、agentType、message，权限范围由服务端当前登录主体决定。
- Agent health 只表示 HTTP 入口可达，不代表 Graph、Analytics、Tool、LLM 分别就绪。

P07 风险中心，建议新增路由 /home/risk-center：
- 风险中心是固定运营与审计页面，不能由安全风控 Agent 代替。
- 使用“分组选择 + 风险总览 + 高风险短链 + 风险事件”的主结构。
- 展示 totalShortLinksScanned、low/medium/high risk count、watchingCount、平均/最高/分组风险分、reason codes、7 天风险趋势、top risk links。
- 风险短链展示 2h/24h/7d PV/UV、风险分、风险等级、reason codes、watchStatus、最新动作、统计完整度；详情 Drawer 展示 metrics、latestSnapshot、recentEvents。
- currentPolicyCoverage=TOP_CARDS_ONLY 时明确说明只有头部卡片有策略详情；disabledCount 缺失时不要显示或推算全组已停用数量。
- 风险事件支持后端已有的分组、targetType 和短链上下文，不加入任意关键词搜索。展示 evidence、recommendedActions、agentSummary、traceId、sessionId、source、eventTime。
- 把三条链路设计为完全独立的状态机：
  A. 人工审核只提交 WATCH、UNWATCH、FALSE_POSITIVE、CONFIRM_RISK、IGNORE，并记录判断，不自动执行或撤销策略。
  B. 安全风控 Agent 的确定性节点可能自动激活 LIMIT_RATE，展示未触发、已激活、失败、传播未知及证据。
  C. 人工停用已有 policy 需要二次确认和稳定 commandId，展示待确认、已提交、已执行、状态待核实、失败。
- 不生成通用的 DISABLE_SHORT_LINK、BLOCK_IP 或 LIMIT_TIME_WINDOW “批准后执行”按钮；不生成全局策略 CRUD、策略重新启用、独立审核历史或任意 commandId 调试台。

P08 安全风控 Agent，路由 /home/agent/security-risk：
- 与投放 Agent 复用 Agent Workspace 组件和同步响应规则，但使用风险证据优先的信息层级。
- agentType 固定为 security-risk。
- 展示风险结论、风险等级、reason codes、窗口指标、数据质量、访问证据、自动 LIMIT_RATE 结果和完成后的 Tool/Graph 轨迹。
- 与风险中心互相导航，但不把固定的风险事件、审核和策略状态全部塞进对话消息。
- 两个 Agent 使用独立 session；切换路由清除旧结果；请求期间禁止切换 Agent 或新建会话；迟到响应不能串入另一 Agent。

P09 账户中心，路由 /home/account：
- 查看 username、phone、realName、mail；username 只读。
- 编辑 mail、phone、realName、password。修改密码必须输入 currentPassword。
- 支持自助停用当前账户；停用必须输入 currentPassword，明确停用后无法自助恢复，成功后清理登录态并返回登录页。
- 生成查看、编辑、保存中、成功、字段失败、当前密码错误、账号状态冲突、服务失败、停用二次确认状态。

五、生成账户初始化状态门

注册成功后必须先完成登录并取得 token；已有用户首次登录也先取得 token。随后才能检查当前账户初始化状态：
- PENDING：阻断依赖默认分组的工作区，显示初始化中、自动轮询和可安全离开的说明。
- FAILED：显示安全的失败原因、nextRetryAt、立即重试；点击重试后继续轮询，不立即宣称成功。
- READY：使用返回的 groupId 进入工作区。
不要创建独立初始化管理中心。

六、建立全局状态矩阵

在 03_RESPONSIVE_PROTOTYPE_QA / State_Matrix 建立可复用状态展示，并把真正改变页面布局的状态应用到对应页面。通用状态优先使用 Component Variants 和矩阵，不把每个状态复制成一张完整页面：
- 全局：first load、local refresh、offline/network error、401、403、429、timeout、retry、success、404。
- 短链：empty group、normal、expiring、expired、title fetching、title failed but editable、submitting、version conflict。
- 批量：row mismatch、8 MiB body exceeded、sync success、VALIDATING、READY、RUNNING、SUCCEEDED、PARTIAL_SUCCESS、FAILED、CANCELLED、export unavailable、export 429。
- 统计：loading、real empty、PARTIAL、unknown、history insufficient、cursor expired、page loading、range over 7 days、scope over 500 links/TOO_LARGE。
- Agent：health checking、HTTP reachable、unreachable、READY、RUNNING、success、business warning、failure、late response discarded。
- 风险：loading、no events、TOP_CARDS_ONLY、UNKNOWN、review submitting/recorded/failed、auto LIMIT_RATE inactive/activated/failed/propagation unknown、policy disable confirmation/submitted/executed/status unknown/failed。
- 账户：unauthenticated、validation、initialization PENDING/FAILED/READY、saving、success、business error、service error、disabled and signed out。

七、响应式画板

- 为全部 9 个页面生成 1440px 桌面主稿。
- 为短链工作区、统计、风险中心、两个 Agent 生成 1280px 紧凑桌面稿。
- 为短链工作区、统计、风险中心、两个 Agent 生成 768px 平板关键稿。
- 为登录、注册、短链行详情、Agent 输入/结果、账户中心生成 390px 关键移动稿。
- 表格在窄屏按业务优先级隐藏列，并提供行详情入口；禁止把所有列强行压缩成不可读内容。
- 在 Responsive_Patterns 定义并映射到 P01–P09 全部页面：1280px 侧栏收起；768/390px 使用紧凑顶栏与导航 Drawer；多列表格转为行摘要 + 全屏详情；图表纵向排列，无法阅读的地图降级为排行列表；所有 Drawer 在窄屏全屏化；Dialog、批量任务、风险证据使用单列全宽承载；两个 Agent 的窄屏顺序固定为范围 → 输入 → 回答 → 证据 → 完成后 Trace；认证和账户表单改为单列。即使不为每页复制 390px 画板，也必须在 Coverage_Matrix 标明每个 P 页面采用的响应模式。

八、建立可点击原型

在 02_PRODUCT_SCREENS / 10_Prototype_Flows 中直接复用同一 Figma Page 内的 P01–P09 页面帧连接流程，不创建另一套演示画板。Flow Starting Points 和全部可点击连接都留在 02_PRODUCT_SCREENS；03_RESPONSIVE_PROTOTYPE_QA / Prototype_Index 只保存流程入口索引。F01、F03、F07、F10、F11 生成完整可点击原型：
1. F01：登录 → 初始化 PENDING/FAILED/READY → 短链工作区；或注册成功 → 使用刚提交的凭据登录/引导登录 → 取得 token → 初始化状态门 → 短链工作区。
2. F03：创建单条短链 → 成功 → 复制/二维码 → 查看统计。
3. F07：统计范围/日期切换 → 维度下钻 → 脱敏访问记录游标翻页。
4. F10：投放分析 Agent READY → RUNNING → 完整结果与完成后 Trace。
5. F11：风险中心 → 风险短链详情 → 提交人工审核；人工停用已有策略 → command 状态核验；安全风控 Agent → 自动 LIMIT_RATE 结果 → 返回风险中心核验。

F02、F04、F05、F06、F08、F09、F12 使用已有页面实例生成包含主要决策节点的紧凑 Storyboard，不为每一步复制完整页面。

九、视觉与内容约束

- 页面文案使用自然、准确的简体中文；使用一致的原型示例数据，不使用 Lorem Ipsum，不暴露真实 Token、内部服务地址或敏感 IP。
- 浏览器只表现同源 /api 业务，不展示 Admin、Command、Agent 等内部地址，也不允许用户填写用户身份、审核人、租户或内部 Token。
- 不创建 APISIX、Kafka、Redis、MySQL、ClickHouse、Flink、Leaf 运维页面。
- 禁止 AI 紫色网格渐变、满屏暗色霓虹、全局玻璃拟态、渐变文字、悬浮发光卡片、Emoji、巨大圆角、低对比灰字、四张完全相同 KPI 卡和无意义循环动画。
- 不要把每组信息都塞进卡片。优先使用分栏、留白、细分隔线、层级背景、表格、上下文 Drawer 和可折叠辅助区。
- 图表必须包含标题、图例、时间范围、统计口径、更新时间、空态和 PARTIAL/unknown 状态。
- 所有简体中文字符必须清晰正确，无乱码、截断、重叠或文字溢出。
- 正文、控件、图表和状态达到 WCAG AA；焦点清晰；状态同时使用图标/文字，不只依赖颜色。

十、固化产品合同

在 00_README_CONTRACT 中写入可视化产品地图、路由表、P01–P09 页面索引、F01–F12 流程索引、关键业务合同和 Do_Not_Build 清单。F01–F12 分别为：认证与回跳、分组管理、单链创建、批量创建、短链编辑、复制与二维码、统计查询、访问记录、回收站生命周期、投放分析 Agent、风险中心与安全风控 Agent、账户与会话。Do_Not_Build 必须包含：独立任务中心、对象上传、任务重试/失败行重跑、独立导出中心、全局策略 CRUD、独立审核历史、任意身份或 commandId 调试、用户/RBAC 管理、找回密码/邮箱验证/MFA、基础设施运维页。

十一、执行优先级

第一次调用的最低交付必须包含 Foundations、唯一组件源、AppShell、9 个 1440px 默认页面、页面必需 Drawer/Dialog，以及 F01、F03、F07、F10、F11 五条核心流程的关键节点。其余状态、响应式和 Storyboard 尽可能在同一次调用完成；任何未完成项都必须在 Coverage_Matrix 标记 MISSING，不能静默省略，也不能用重复组件换取速度。执行顺序：
1. Foundations、Variables、共享 Components、AppShell。
2. 9 个 1440px 页面默认稿和页面必需 Drawer/Dialog。
3. F01、F03、F07、F10、F11 五条完整可点击原型，以及其余流程的紧凑 Storyboard。
4. 全局状态矩阵。
5. 1280/768/390 响应式画板与全页面响应规则。
6. 装饰动效和辅助展示。

完成后在 00_README_CONTRACT 放置交付索引，在 03_RESPONSIVE_PROTOTYPE_QA / Coverage_Matrix 标记 P01–P09、F01–F12、8 组组件族和响应式覆盖情况。不要只返回文字报告；必须先完成设计对象。
```

## 3. 第二次调用：审计补全提示词

第一次生成完成后，在同一个 Figma 文件中复制以下提示词。它把审计和修复合并成一次调用：

```text
继续处理当前 ShortLink Figma 文件。不要重新设计，不要创建第二套 Design System，不要复制已有页面，也不要只输出审计报告。请先读取现有 Pages、Sections、Variables、Components、Variants 和 Prototype connections，然后在原对象上直接修复；只创建确实缺失的对象。

按下面顺序完成一次“盘点 → 修复 → 补齐 → 最终 QA”：

1. 文件结构：先识别当前采用结构 A（00_README_CONTRACT、01_DESIGN_SYSTEM、02_PRODUCT_SCREENS、03_RESPONSIVE_PROTOTYPE_QA 四个 Figma Pages）还是合法降级结构 B（一个 Figma Page 下四个同名顶层 Sections）。沿用已有结构，不做 A/B 转换，不重复创建。确认其既定子 Sections，缺失时补齐。先在 Coverage_Matrix 将 P01–P09、F01–F12、8 组组件族和断点标记为 PASS、PARTIAL 或 MISSING。
2. 页面：确认 9 个一级页面都存在 1440px 主稿：登录、注册、短链工作区、回收站、统计详情、投放分析 Agent、风险中心、安全风控 Agent、账户中心。
3. 组件复用：查找 detached、重复或外观相同的按钮、输入、导航、表格、状态、Drawer、Agent、风险组件；合并到 01_DESIGN_SYSTEM 的共享组件和 Variants，并重新绑定页面实例。
4. Auto Layout 与样式：修复绝对定位造成的脆弱布局；颜色、间距、尺寸、圆角绑定 Variables，字体绑定 Text Styles，阴影绑定 Effect Styles；页面只使用 Semantic Variables，不直接使用 Primitive 色值。组件包含 default、hover、focus、active、disabled、loading。
5. 短链合同：移除无后端支持的关键词搜索、任意维度筛选和批量修改；保留 gid/current/size/orderTag。检查 2–500 行同步、501–50,000 行异步 job，并同时校验 8 MiB 请求体上限；job 状态是 VALIDATING、READY、RUNNING 和四种终态 SUCCEEDED、PARTIAL_SUCCESS、FAILED、CANCELLED；同时检查 after+limit 结果游标、取消、同步 XLSX、异步终态 CSV、429、expectedVersion 冲突。回收站不能显示响应中不存在的 recycleTime/delTime。
6. 统计合同：确认 DateRange 最多 7 天、分组范围最多 500 条短链并覆盖 TOO_LARGE；确认 PV/UV/UIP 语义没有混淆；移除同比/环比和浏览器历史统计 job 入口；ISP 不写成 Wi-Fi/4G/5G；新老访客使用短链/分组历史首次观测；PARTIAL、unknown、历史范围和 snapshot cursor 明确可见。
7. Agent 合同：两个 Agent 独立 session；message 最多 2000 字；请求中禁止切换 Agent/新建会话；RUNNING 只显示通用占位；移除任何虚假流式文字和实时 Tool/Graph 节点；健康只标示 HTTP 入口可达；范围控件只转换成 message 文本。
8. 风险合同：风险中心必须独立于安全风控 Agent。把人工审核、自动 LIMIT_RATE、人工停用已有策略保持为三套不同状态机。审核不等于执行；停用策略使用稳定 commandId 并覆盖状态待核实；删除通用 DISABLE_SHORT_LINK、BLOCK_IP、LIMIT_TIME_WINDOW 批准按钮。TOP_CARDS_ONLY 和 UNKNOWN 必须可见，disabledCount 缺失时不能推算。
9. 认证与账户合同：“记住登录”只持久保存 token/username，不保存密码；确认注册成功后先取得登录 token，再查询初始化；补齐初始化 PENDING/FAILED/READY、nextRetryAt、手动重试后继续轮询；username 只读；改密和停用要求 currentPassword；停用不可自助恢复并在成功后退出。
10. 状态完整性：补齐 loading、empty、error、permission、401、403、429、timeout、partial、unknown、version conflict、destructive confirmation、success。状态不能只靠颜色。
11. 视觉修复：保持 Signal Workbench 的明亮、丰富、精密风格。清理灰暗区块、杂乱渐变、重复卡片和不一致图标；全局交互色只用 Electric Cobalt，模块色和风险色只作数据/语义表达。
12. 排版 QA：逐个检查所有中文字符、数字、URL、jobId、commandId、表头、按钮和图例。修复乱码、错字、截断、重叠、过窄行高和顶部留白不足；图标距容器顶部至少 16px，图标与标题 10–12px，正文行高至少 1.5，按钮文字不换行。
13. 响应式：补齐遗漏的 1280px、768px、390px 关键稿及 P01–P09 的响应规则；表格窄屏按优先级隐藏列并提供详情入口，不压缩所有列。
14. 原型：在 02_PRODUCT_SCREENS / 10_Prototype_Flows 中确认 F01、F03、F07、F10、F11 五条核心流程可完整点击走通；F02、F04、F05、F06、F08、F09、F12 有紧凑 Storyboard。连接直接复用同一 Page 的页面实例，03_RESPONSIVE_PROTOTYPE_QA 只维护 Prototype_Index。
15. 最终输出：在 03_RESPONSIVE_PROTOTYPE_QA 更新 Coverage_Matrix 和 Final_QA，逐项标记 PASS、PARTIAL 或 MISSING；先修复 PARTIAL/MISSING，再记录仍受工具能力限制的项目。不要生成长篇说明代替实际修复。
```

## 4. 只有第二次仍失败时才使用的定点提示词

不要预先调用第三次。仅当第二次结束后仍存在明显缺页、乱码或组件脱离时，选中具体 Section/Frames，再用一句定点指令修复，例如：

```text
只修复当前选中的 Frames：复用现有 Variables 和 Components，补齐缺失状态，修复中文乱码、文本溢出、图标顶部留白与 Auto Layout；不要改变业务结构，不要新建设计系统，不要修改未选中的页面。完成后直接更新原对象。
```
