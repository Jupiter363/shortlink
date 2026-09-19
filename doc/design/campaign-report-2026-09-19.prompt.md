# 投放分析报告效果图生成记录

方式：内置 imagegen。用途：布局概念预览，非业务截图；图中全部数字为虚构示例，不作为真实分析结果。

用户最新方向：保留完整分析深度，图表与文本穿插呼应，使用轻量卡通风格，避免大段文本连续堆叠。卡片数量和短句不是分析内容上限；复杂问题可以扩展有依据的章节。

效果图：`campaign-report-2026-09-19.png`。

复核备注：图中头部短链对增长的贡献需要真实分短链基期数据方可判断，不能仅凭当前占比推断。正式实现只允许后端验证过的事实进入结论及变化率。生成图里的装饰、数值和文案均以正式协议为准。

## 最终提示词

Use case: ui-mockup.
Create one polished high-fidelity desktop web application result-page mockup, front-on flat screen image, no monitor/device framing. This is a PREVIEW concept for the existing Chinese JUPITER RELAY / 木星中继站 campaign analysis Agent. Large landscape-ish full-page canvas about 1800 by 1500, crisp legible Simplified Chinese typography, high-end carefully designed product UI. The user explicitly wants a LIGHTWEIGHT CARTOON ANALYTICAL DASHBOARD with FULL, MEANINGFUL ANALYSIS interwoven with data visualization, NOT an essay wall, NOT an oversimplified collection of KPI tiles. Preserve analytical depth by pairing each chart with 2-3 short, substantive paragraphs, factual callouts, hypotheses clearly marked, and suggested verification. Text and charts must directly respond to each other.

Existing brand: very pale ice-blue #f5f9ff canvas, white #ffffff panels, navy #182847 text, cobalt #2f55e7 accent, butter yellow #ffd665 highlights, pale mint supporting chip, subtle #d7e1ee borders. Rounded 16px cards, excellent whitespace discipline, human Chinese sans-serif like MiSans. Small friendly illustrated Jupiter planet mascot, warm striped planet body and two small eyes; mostly flat hand-drawn cartoon accent, NOT a huge robot, no childish overload, no gradients/glass/neon. Occasional restrained offset dark-blue shadow on a key action only. Analytical chart lines and tables are sharp and precise.

Composition: a slim 175px pale navigation sidebar at left, with a small Jupiter logo, “木星中继站”, nav “短链管理”, “访问统计”, “风险中心”, selected “投放分析”, “安全风控”, and a tiny bottom user avatar. Main report occupies the remaining width, not a chat box; no giant prompt form, no permanent evidence directory wasting the side. Simple top app header “投放分析” with small breadcrumb “分析报告”. Put “设计示意 · 虚构数据” clearly near upper right. All numeric values in this mockup are illustrative, not real queried data.

Main report in reading order:
1) Compact scope and report actions strip: “默认分组”, “09.12 — 09.18”, “Asia/Shanghai”, mint label “统计完成”. Small secondary buttons “查看依据”, “导出报告”, primary blue “继续分析”. Under this, an expressive but compact key-conclusion row with a small Jupiter mascot and yellow label “本次结论”. Title verbatim: “访问量上升，增长主要由头部短链带动”. Subtext: “先看增长发生在哪里，再验证增长背后的原因。” No huge landing-page hero.
2) One unified compact horizontal metric band, NOT four floating equal cards: “总访问 PV” big “1,300” with small “较上期 +30.0%”; “独立访客 UV” big “930”; “独立 IP” big “540”; right small caption “对比基期 09.05 — 09.11”. Small question-mark method icons next to metric labels. Easy to scan, warm personality without hiding data.
3) A wide analysis section with an understated small chapter marker “01 / 访问变化”, title “增长并非均匀发生”. This is the main visual anchor, about 60% chart on left, 40% integrated interpretation on right. White card with chart baseline, extremely subtle horizontal grid, blue smooth line and lightly shaded underfill, yellow peak marker. Daily values for 09.12–09.18: 120,160,170,150,260,210,230. Label peak “09.16 · 260 次”, one blue callout “高峰之后仍高于前半周”. On the right have distinct small headed narrative segments, not one text wall:
“发现” then “9 月 16 日达到本期高点，随后两日仍高于前半周。”
“如何理解” then “这说明增长不只发生在峰值当天。仍需结合短链和设备分布，判断增长是否集中在单一来源。”
Small outlined evidence chips “依据：日趋势” and “核查高峰来源 ↗”.
4) Next row is deliberately different proportions, a broad two-thirds “02 / 增长来自哪里” card and a one-third “解释边界” supporting note.
In the broad card, title “头部短链贡献了更多访问”. Two horizontal ranking bars, blue main bar “短链 A” value “800” share “61.5%”, smaller sky-blue bar “短链 B” value “500” share “38.5%”. Under the bars a meaningful interpretation block separated by a fine divider, with a tiny yellow lightbulb-style accent and text “头部集中，需要结合规模判断”. Two short paragraphs: “短链 A 占本期访问的大部分，是后续核查的优先对象。” / “访问贡献不等于转化贡献。缺少投放费用和转化记录时，不能直接据此增加预算。” Below a subtle link “查看完整排名与统计口径 ↗”.
Supporting note pale yellow-white, small outlined question icon, title “还不能直接归因于投放效果”. Three clearly spaced brief points: “访问增长 ≠ 转化提升”, “未接入费用与转化数据”, “来源集中是否正常，仍需进一步核查”. Tiny pill “待验证假设”, sentence “可能与渠道曝光变化有关，需补充投放记录验证。” This is reasoned analytical content, not a warning wall.
5) Bottom full-width “03 / 下一步验证” section with three compact actionable rows rather than three big equal cards, each with a small circled step and two layers of text plus a small arrow button at right:
“拆解高峰日” / “按设备与省份查看峰值构成，核查增长是否集中。”
“对比头部短链” / “比较短链 A 与 B 的访客结构，识别不同访问特征。”
“补充转化依据” / “结合转化与费用记录，再判断投放收益。”
Footer subtle note “统计口径与数据覆盖” with a question mark and “本页为设计示意，数据仅用于展示”; secondary link “执行记录”.

The result must feel like a distinctive, calm, professional yet lightly cartoon Jupiter report. Rich analytical content remains visible, subdivided and paired with visuals. Large enough body text, balanced panel sizing, direct relationship between evidence and conclusion, no giant blank spaces, no random pie charts or gauges, no repeated avatars, no JSON/code shown, no stock generic dashboard wall. Entire composition visible as a carefully art-directed UI mockup.
