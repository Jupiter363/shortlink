# P2：真实筛选集合的派生查询范围

日期：2026-09-20。关联 Issue #62，承接 E19/E20。此批实现下钻前的集合与来源约束，尚未把 `dimension_change` 查询注册为 NativeGraph Skill。

## 集合与来源

`inspectPair` 只接受同一个已封存集合、同一最终 LOCAL 发布的 selectedEntities / selectionEvidence。它验证实际页链、索引和输入产物，读取每个源页 LOCAL 调用中冻结的两期日期并核对一致；期间引用字符串不能代替日期证据。读取返回前再次核验当前权限与期限。

新增按短链 ID 升序的入选分页，保留原按下降差值排序和全证据分页；三种游标各有明确排序标识，不可混用。

`CampaignSelectedScope` 每次只保留一页入选行和一个请求分片，通过流式规范哈希构造独立的 `SelectedScopeArtifact`。小 manifest 引用筛选集合、证据、原范围的 ID/hash 和原两期；不内嵌全部成员。其派生 scopeRef 独立于原分组 scopeRef，即使全部成员都入选也不冒充权威分组枚举。

原 `enumerationVersion` 仅作为 `SOURCE_GROUP_MEMBERSHIP` 来源保留，`groupScopeComplete=false`。后续冻结查询按明确成员重新授权；每片最多 500 个 ID，按 ID 稳定升序，不能用差值排序直接切片。空集保留 `NO_DECLINES` 或 `INSUFFICIENT_EVIDENCE`，不生成查询分片。

## 耐久发布与最小验证

派生结果经注册的 LOCAL 边界发布，冻结全部来源 metadata、日期、输出身份和不晚于来源的期限。READY 复用同一产物；公开的 read-only prepare 支持后续恢复适配器重建批准对象。本批不声称该适配器已经接入维度 Skill。

`CampaignSelectedScopeTest` 的 2 项定向后端测试首次全部通过，失败、错误、跳过均为 0；日志 `.work/selected-scope-tests.log`。用真实 H2 账本、来源统计页、筛选索引及 LOCAL 发布验证 502 候选中 501 入选，ID 500＋1 分片、独立哈希、源版本／期限、重开复用、三种游标六向隔离、错配来源／日期／撤权／过期拒绝及两类空集。未启动 Docker、应用、模型或前端。

## 后续边界

两期真实联合维度查询、分片 cohort 的 PV 分母／独立 UV/UIP、逐页维度变化证据、STEP_OUTPUT 动态绑定与原生恢复仍是下一项。现有来源页链每次读取都完整复核，内存按页有界但不代表大集合吞吐已验；本批未做性能或 MySQL 环境验收。
