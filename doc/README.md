# 项目文档

`doc/` 是项目文档总目录，开发计划、压测报告、统计说明和集成说明统一在这里维护。

| 目录 | 内容 |
| --- | --- |
| [development](development/repository-layout.md) | 当前开发入口、模块职责、构建方式与仓库布局 |
| [plan](plan/README.md) | 生产级重构、Agent 开发计划、任务拆分与验收要求 |
| [压测报告](压测报告/README.md) | 压测过程报告、按日期归档的结果与配置、校验凭据 |
| [analytics](analytics/runtime.md) | 统计运行、恢复与[查询任务](analytics/query-jobs.md)说明 |
| [integration](integration/component-adapters.md) | 中间件适配与组件集成验收说明 |
| [images](images/) | 文档图片资源 |

当前入口：[APISIX 单网关与 Admin 鉴权](development/single-gateway.md)。

常用入口：[生产级重构计划](plan/生产级重构增强/01-开发阶段与任务拆分.md) · [真实跳转执行记录](压测报告/过程报告/27-真实跳转按序突破执行记录.md) · [2026-09-09 压测归档](压测报告/2026-09-09/README.md)。

合并验收：[2026-09-09 main 合并前检查](integration/main-merge-2026-09-09/README.md)（Issue #2 / PR #3）。

仓库整理：[2026-09-10 分阶段验收](integration/repository-layout-2026-09-10/README.md)（Issue #6）。

后续开发计划放入 `doc/plan/`，压测执行与分析报告放入 `doc/压测报告/过程报告/`，正式留档按日期或批次创建子目录。部署文件及其使用说明仍可从[部署入口](../deploy/README.md)查阅。
