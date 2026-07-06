# Agent Service 第一阶段 API 联调实施计划

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 新增独立 `agent-service` 顶级 Maven module，完成 DeepSeek V4 Flash API 联调入口、基础 chat/health API、MySQL checkpoint 基础结构、轻量 Agent Console，并保持后续 Spring AI Alibaba Graph 接入边界清晰。

**Architecture:** `agent-service` 采用 `harness / agent / tool / business / infrastructure / common` 分层。第一批代码以 API 联调和可启动为目标：Graph 先通过 `CampaignAnalysisGraphExecutor` 接口与 mock 执行器落位，LLM 通过 `DeepSeekChatClient` 走 OpenAI-compatible HTTP 请求，工具层先提供注册表和 mock tool，后续再接 admin internal API。

**Tech Stack:** Java 17、Spring Boot 3.0.7、Spring AI Alibaba Agent Framework 1.1.2.3、Spring AI Alibaba Graph Core 1.1.2.3、DeepSeek OpenAI-compatible HTTP API、MyBatis-Plus、MySQL、JUnit 5。

---

## File Structure

```text
pom.xml
agent-service/pom.xml
agent-service/src/main/java/com/nageoffer/shortlink/agent/
  ShortLinkAgentApplication.java
  harness/api/
  harness/runtime/
  harness/checkpoint/
  agent/graph/
  agent/state/
  tool/core/
  tool/registry/
  tool/springai/
  business/api/
  business/service/
  infrastructure/config/
  infrastructure/llm/
  infrastructure/persistence/
  common/result/
agent-service/src/main/resources/
  application.yaml
  sql/agent_service_schema.sql
  static/agent-console/index.html
agent-service/src/test/java/com/nageoffer/shortlink/agent/
```

## Task 1: Maven Module And Configuration

**Files:**
- Modify: `pom.xml`
- Create: `agent-service/pom.xml`
- Create: `agent-service/src/main/resources/application.yaml`
- Test: `agent-service/src/test/java/com/nageoffer/shortlink/agent/infrastructure/config/AgentPropertiesTest.java`

- [ ] Step 1: Write failing test for default DeepSeek and agent config.
- [ ] Step 2: Run `mvn -pl agent-service test -Dtest=AgentPropertiesTest` and verify it fails because the module/classes do not exist.
- [ ] Step 3: Add `agent-service` module, dependencies, `AgentProperties`, `DeepSeekProperties`, and `application.yaml`.
- [ ] Step 4: Run the same test and verify it passes.

## Task 2: Health And Runtime Chat API

**Files:**
- Create: `agent-service/src/main/java/com/nageoffer/shortlink/agent/ShortLinkAgentApplication.java`
- Create: `agent-service/src/main/java/com/nageoffer/shortlink/agent/harness/api/HealthController.java`
- Create: `agent-service/src/main/java/com/nageoffer/shortlink/agent/harness/api/AgentChatController.java`
- Create: `agent-service/src/main/java/com/nageoffer/shortlink/agent/harness/runtime/AgentRunHarness.java`
- Create: `agent-service/src/main/java/com/nageoffer/shortlink/agent/harness/runtime/AgentRunRequest.java`
- Create: `agent-service/src/main/java/com/nageoffer/shortlink/agent/harness/runtime/AgentRunResult.java`
- Test: `agent-service/src/test/java/com/nageoffer/shortlink/agent/harness/api/AgentChatControllerTest.java`

- [ ] Step 1: Write failing MVC test for `GET /internal/short-link-agent/v1/health`.
- [ ] Step 2: Write failing MVC test for `POST /internal/short-link-agent/v1/chat`.
- [ ] Step 3: Implement controllers and runtime DTOs with a mock harness response.
- [ ] Step 4: Run `mvn -pl agent-service test -Dtest=AgentChatControllerTest` and verify both tests pass.

## Task 3: DeepSeek API Client

**Files:**
- Create: `agent-service/src/main/java/com/nageoffer/shortlink/agent/infrastructure/llm/DeepSeekChatClient.java`
- Create: `agent-service/src/main/java/com/nageoffer/shortlink/agent/infrastructure/llm/DeepSeekChatRequest.java`
- Create: `agent-service/src/main/java/com/nageoffer/shortlink/agent/infrastructure/llm/DeepSeekChatResponse.java`
- Create: `agent-service/src/main/java/com/nageoffer/shortlink/agent/infrastructure/llm/LlmChatClient.java`
- Test: `agent-service/src/test/java/com/nageoffer/shortlink/agent/infrastructure/llm/DeepSeekChatClientTest.java`

- [ ] Step 1: Write failing test using `MockRestServiceServer` that verifies Authorization header uses `DEEPSEEK_API_KEY`/`LLM_API_KEY` supplied property value and model defaults to `deepseek-v4-flash`.
- [ ] Step 2: Implement `DeepSeekChatClient` with `RestTemplate`.
- [ ] Step 3: Run `mvn -pl agent-service test -Dtest=DeepSeekChatClientTest`.

## Task 4: Tool Facade Skeleton

**Files:**
- Create: `agent-service/src/main/java/com/nageoffer/shortlink/agent/tool/core/AgentTool.java`
- Create: `agent-service/src/main/java/com/nageoffer/shortlink/agent/tool/core/ToolContext.java`
- Create: `agent-service/src/main/java/com/nageoffer/shortlink/agent/tool/core/ToolDescriptor.java`
- Create: `agent-service/src/main/java/com/nageoffer/shortlink/agent/tool/core/ToolResult.java`
- Create: `agent-service/src/main/java/com/nageoffer/shortlink/agent/tool/registry/AgentToolRegistry.java`
- Test: `agent-service/src/test/java/com/nageoffer/shortlink/agent/tool/registry/AgentToolRegistryTest.java`

- [ ] Step 1: Write failing registry test for unique tool names and lookup.
- [ ] Step 2: Implement tool core records/interfaces and registry.
- [ ] Step 3: Run `mvn -pl agent-service test -Dtest=AgentToolRegistryTest`.

## Task 5: MySQL Checkpoint Skeleton

**Files:**
- Create: `agent-service/src/main/java/com/nageoffer/shortlink/agent/harness/checkpoint/GraphCheckpoint.java`
- Create: `agent-service/src/main/java/com/nageoffer/shortlink/agent/harness/checkpoint/GraphCheckpointStore.java`
- Create: `agent-service/src/main/java/com/nageoffer/shortlink/agent/infrastructure/persistence/JdbcGraphCheckpointStore.java`
- Create: `agent-service/src/main/resources/sql/agent_service_schema.sql`
- Test: `agent-service/src/test/java/com/nageoffer/shortlink/agent/infrastructure/persistence/JdbcGraphCheckpointStoreTest.java`

- [ ] Step 1: Write failing H2-backed test for save/load checkpoint JSON.
- [ ] Step 2: Implement JDBC checkpoint store and schema SQL.
- [ ] Step 3: Run `mvn -pl agent-service test -Dtest=JdbcGraphCheckpointStoreTest`.

## Task 6: Lightweight Agent Console

**Files:**
- Create: `agent-service/src/main/resources/static/agent-console/index.html`
- Test: `agent-service/src/test/java/com/nageoffer/shortlink/agent/harness/api/AgentConsoleStaticResourceTest.java`

- [ ] Step 1: Write failing MVC/static resource test for `/agent-console/index.html`.
- [ ] Step 2: Add minimal static console that calls health and chat endpoints.
- [ ] Step 3: Run `mvn -pl agent-service test -Dtest=AgentConsoleStaticResourceTest`.

## Task 7: Module Verification

**Files:**
- Modify as needed only within `agent-service/**` and root `pom.xml`.

- [ ] Step 1: Run `mvn -pl agent-service test`.
- [ ] Step 2: Run `mvn -pl agent-service -DskipTests package`.
- [ ] Step 3: Document any unresolved full-reactor baseline failures caused by pre-existing modules.

## Self-Review

- Spec coverage: covers top-level module, DeepSeek API联调, health/chat API, Tool Facade skeleton, MySQL checkpoint skeleton, Agent Console skeleton, and verification.
- Placeholder scan: no open placeholder is used as an implementation requirement; optional future admin integration remains outside this first implementation batch.
- Type consistency: package names follow `harness / agent / tool / business / infrastructure / common`; admin integration is represented by `AdminApiClient` in later tasks, not by MCP or remote tool protocol.
