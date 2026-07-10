# Agent 生产就绪增强 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 将现有 CampaignAnalysis Agent、SecurityRisk Agent、风险画像和策略拦截组件升级为可自动调度、可重试、可人工确认、可恢复、可观测的生产后端体系。

**Architecture:** `agent-service` 新增数据库批次/任务状态机、通用待确认动作、有效策略槽位和 Outbox；Spring AI Alibaba Graph 继续负责 Agent 节点编排，并接入原生 Saver；Harness 统一处理身份、脱敏、会话、HTTP 韧性和运行观测；admin 仅提供正式 facade 和可信 internal tool API。

**Tech Stack:** Java 17、Spring Boot 3.0.7、Spring AI Alibaba Graph 1.1.2.x、JdbcTemplate、MySQL/H2、Redis、Spring Retry、Micrometer Observation、JUnit 5、MockMvc。

---

## 0. 执行边界

```text
本轮先完成后端组件和模块级自动化测试。
不把真实环境 E2E 作为每个任务的完成前提。
每个阶段必须遵循 RED -> GREEN -> REFACTOR。
每个阶段通过测试后单独提交并推送。
不得提交 application*.yaml、密钥、salt、token 或本机数据库配置。
```

## 1. 子计划

| 子计划 | 目标 | 主要模块 |
|---|---|---|
| [00_总控索引](./02_Agent生产就绪增强实施计划/00_总控索引.md) | 状态、顺序和依赖 | plan |
| [01_风控生产编排与批处理可靠性](./02_Agent生产就绪增强实施计划/01_风控生产编排与批处理可靠性.md) | batch/job、结构化 Graph 输入、失败隔离 | agent-service |
| [02_待确认动作与策略一致性](./02_Agent生产就绪增强实施计划/02_待确认动作与策略一致性.md) | pending action、人工处置、有效策略、Outbox | agent-service/admin |
| [03_Harness生产能力](./02_Agent生产就绪增强实施计划/03_Harness生产能力.md) | 校验、身份、Saver、记忆、脱敏、HTTP 韧性、观测 | agent-service |
| [04_Agent能力增强](./02_Agent生产就绪增强实施计划/04_Agent能力增强.md) | 结构化风控解释、Campaign Planner 和写动作 | agent-service/admin |
| [05_验证提交与迁移](./02_Agent生产就绪增强实施计划/05_验证提交与迁移.md) | 回归、迁移、敏感扫描、提交推送 | 全部相关模块 |

## 2. 执行顺序

```text
第一批：生产编排与批处理可靠性
第二批：待确认动作与策略一致性
第三批：Harness 生产能力
第四批：两个 Agent 的能力增强
第五批：模块回归、迁移检查、最终审查
```

后续批次依赖前一批次的数据模型和接口，不并行修改同一组核心文件。

## 3. 总体验收命令

```bash
mvn -pl agent-service test
mvn -pl admin test
mvn -pl gateway test
mvn -pl project test
git diff --check
git status --short --branch
```

敏感信息扫描：

```bash
rg -n "sk-[A-Za-z0-9]{16,}|Bearer\\s+[A-Za-z0-9._-]{16,}|DEEPSEEK_API_KEY\\s*[:=]\\s*sk-|AGENT_INTERNAL_TOKEN\\s*[:=]\\s*[^}\\s]+|RISK_HASH_SALT\\s*[:=]\\s*[^}\\s]+|jdbc:[^\\s]+:[^\\s]+@" agent-service admin gateway project plan scripts
```

