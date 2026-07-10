# 02D Admin 确认入口与页面 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 在 admin 模块提供受 gid 所有权保护的动作查询、确认和拒绝接口，并在现有 Agent 管理页面展示动作生命周期和 Redis 同步状态。

**Architecture:** Admin 从 `UserContext` 获取可信身份，查询 GroupDO 验证 gid 后，通过 OpenFeign 调用 agent-service internal API。静态页面只调用 admin public API，不直接访问 agent-service，不展示原始 payload、execution token 或完整 ipHash。

**Tech Stack:** Spring MVC、MyBatis-Plus、Spring Cloud OpenFeign、MockMvc、Recording HttpServer、HTML/CSS/JavaScript、Figma。

---

## Task 1: 在 Figma 中建立确认流视觉合同

**Files:**

- Reference: `admin/src/main/resources/static/agent-admin/index.html`
- Reference: `plan/Agent平台增强/03_待确认动作与策略一致性详细设计.md`

- [ ] **Step 1: 加载 Figma 写入技能**

执行时必须依次加载：

```text
figma:figma-create-new-file
figma:figma-use
figma:figma-generate-design
```

- [ ] **Step 2: 创建设计文件**

创建名为：

```text
ShortLink Agent Admin - Pending Actions
```

的 Figma Design 文件。

- [ ] **Step 3: 设计五类状态**

```text
动作列表：gid、动作类型、状态、目标、创建时间、过期时间。
动作详情：安全摘要、证据摘要、版本和执行结果。
确认对话框：动作影响、目标和确认备注。
拒绝对话框：原因、IGNORE/FALSE_POSITIVE。
同步状态：PENDING/SYNCED/RETRY_WAIT/DEAD。
```

控件要求：

```text
筛选使用 select/menu。
状态使用低饱和 badge。
确认和拒绝使用明确命令按钮。
处理中按钮禁用并带 spinner。
不使用营销式卡片、渐变、装饰性大标题。
页面信息密度与现有 admin 页面一致。
```

- [ ] **Step 4: 记录 Figma URL**

将最终 Figma URL 写入本阶段总控索引的 02D 状态说明，不把截图或导出二进制提交到仓库。

## Task 2: 定义 Admin Feign DTO 和 Remote Service

**Files:**

- Create: `admin/src/main/java/com/nageoffer/shortlink/admin/remote/AgentPendingActionRemoteService.java`
- Create: `admin/src/main/java/com/nageoffer/shortlink/admin/remote/config/AgentPendingActionFeignConfiguration.java`
- Create: `admin/src/main/java/com/nageoffer/shortlink/admin/remote/error/AgentActionRemoteException.java`
- Create: `admin/src/main/java/com/nageoffer/shortlink/admin/remote/error/AgentActionRemoteErrorDecoder.java`
- Create: `admin/src/main/java/com/nageoffer/shortlink/admin/remote/dto/req/AgentActionConfirmReqDTO.java`
- Create: `admin/src/main/java/com/nageoffer/shortlink/admin/remote/dto/req/AgentActionRejectReqDTO.java`
- Create: `admin/src/main/java/com/nageoffer/shortlink/admin/remote/dto/resp/AgentPendingActionRespDTO.java`
- Create: `admin/src/main/java/com/nageoffer/shortlink/admin/remote/dto/resp/AgentActionPageRespDTO.java`
- Create: `admin/src/test/java/com/nageoffer/shortlink/admin/remote/AgentPendingActionRemoteServiceTest.java`

- [ ] **Step 1: 写 Feign 路径和 Header 失败测试**

```java
@Test
void confirmUsesInternalPathTrustedHeadersAndExpectedGid() {
    AgentActionConfirmReqDTO request = new AgentActionConfirmReqDTO();
    request.setExpectedGid("g1");
    request.setExpectedVersion(2L);
    request.setNote("confirmed by owner");

    Result<AgentPendingActionRespDTO> result = remoteService.confirm(
            "internal-token",
            "trusted-user",
            "1001",
            "Trusted User",
            "action-1",
            request
    );

    assertThat(result.isSuccess()).isTrue();
    assertThat(server.lastRequest.method()).isEqualTo("POST");
    assertThat(server.lastRequest.path())
            .isEqualTo("/internal/short-link-agent/v1/actions/action-1/confirm");
    assertTrustedHeaders();
    assertThat(server.lastRequest.body()).contains("\"expectedGid\":\"g1\"");
}
```

补充 list/detail/reject 测试和 query 参数测试。

增加 403/409/500 失败映射测试：

```java
@Test
void preservesAgentActionErrorCodeAndHttpStatus() {
    server.enqueue(409, """
            {"code":"ACTION_VERSION_CONFLICT","message":"expectedVersion is stale"}
            """);

    assertThatThrownBy(() -> remoteService.confirm(
            "internal-token", "trusted-user", "1001", "Trusted User", "action-1", request()
    )).isInstanceOfSatisfying(AgentActionRemoteException.class, ex -> {
        assertThat(ex.status()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(ex.getErrorCode()).isEqualTo("ACTION_VERSION_CONFLICT");
        assertThat(ex.getErrorMessage()).isEqualTo("expectedVersion is stale");
    });
}
```

分别覆盖 403 `ACTION_SCOPE_FORBIDDEN`、409 `ACTION_VERSION_CONFLICT`、500 `ACTION_EXECUTION_FAILED`；空 body、非 JSON body 和超长 message 使用稳定 fallback，不能把 `FeignException`、内部 token 或响应堆栈暴露给 Controller。

- [ ] **Step 2: 运行测试并确认 RED**

```powershell
mvn -q -pl admin -Dtest=AgentPendingActionRemoteServiceTest test
```

Expected: FAIL，Feign interface、decoder 和远程异常尚不存在。

- [ ] **Step 3: 实现 DTO**

DTO 使用现有 admin 风格：

```java
@Data
@NoArgsConstructor
@AllArgsConstructor
public class AgentActionConfirmReqDTO {
    private String expectedGid;
    private Long expectedVersion;
    private String note;
}

@Data
@NoArgsConstructor
@AllArgsConstructor
public class AgentActionRejectReqDTO {
    private String expectedGid;
    private Long expectedVersion;
    private String reason;
    private String reviewAction;
}
```

`reviewAction` 只允许空值、`IGNORE`、`FALSE_POSITIVE`。`AgentPendingActionRespDTO` 只包含详细设计第 14.3 节安全字段；`result` Map 保留 `policyId/policyKey/policyVersion/policyStatus/syncStatus`，`failure` 使用安全 Map，不增加原始 payload 字段。

- [ ] **Step 4: 实现 Feign interface**

```java
@FeignClient(
        value = "short-link-agent-actions",
        url = "${short-link.agent.admin.remote-url:}",
        configuration = AgentPendingActionFeignConfiguration.class
)
public interface AgentPendingActionRemoteService {
}
```

所有方法传递：

```text
X-Agent-Internal-Token
X-Agent-Username
X-Agent-UserId
X-Agent-RealName
```

`AgentActionRemoteErrorDecoder` 使用 Jackson 解析 agent-service 的 `Result` 错误体，保留受支持的业务错误码和原 HTTP status，抛出 `AgentActionRemoteException`。未知 code 映射为 `AGENT_ACTION_REMOTE_ERROR`，message 先脱敏再截断；禁止直接返回 `FeignException.contentUTF8()`。

- [ ] **Step 5: 运行测试并提交**

```powershell
mvn -q -pl admin -Dtest=AgentPendingActionRemoteServiceTest test
git add admin/src/main/java/com/nageoffer/shortlink/admin/remote admin/src/test/java/com/nageoffer/shortlink/admin/remote/AgentPendingActionRemoteServiceTest.java
git commit -m "feat: add pending action feign client"
```

## Task 3: 实现 Admin Facade 和 gid 所有权保护

**Files:**

- Create: `admin/src/main/java/com/nageoffer/shortlink/admin/common/convention/errorcode/AgentActionAdminErrorCode.java`
- Create: `admin/src/main/java/com/nageoffer/shortlink/admin/service/AgentPendingActionFacadeService.java`
- Create: `admin/src/main/java/com/nageoffer/shortlink/admin/service/impl/AgentPendingActionFacadeServiceImpl.java`
- Create: `admin/src/test/java/com/nageoffer/shortlink/admin/service/AgentPendingActionFacadeServiceTest.java`

- [ ] **Step 1: 写所有权和身份失败测试**

```java
@Test
void confirmIgnoresSpoofedIdentityAndUsesCurrentUser() {
    UserContext.setUser(new UserInfoDTO("1001", "trusted-user", "Trusted User"));
    when(groupService.count(any(Wrapper.class))).thenReturn(1L);
    AgentActionConfirmReqDTO request = new AgentActionConfirmReqDTO();
    request.setExpectedGid("g1");
    request.setExpectedVersion(2L);
    request.setNote("confirm");

    facade.confirm("action-1", request);

    verify(remoteService).confirm(
            "internal-token",
            "trusted-user",
            "1001",
            "Trusted User",
            "action-1",
            request
    );
}

@Test
void rejectsGidOutsideCurrentUserBeforeFeignCall() {
    UserContext.setUser(new UserInfoDTO("1001", "trusted-user", "Trusted User"));
    when(groupService.count(any(Wrapper.class))).thenReturn(0L);

    assertThatThrownBy(() -> facade.list("other-gid", null, null, null, 1, 10))
            .isInstanceOf(ClientException.class)
            .hasMessage("Agent action request gid is not owned by current user")
            .extracting("errorCode")
            .isEqualTo("ACTION_SCOPE_FORBIDDEN");
    verifyNoInteractions(remoteService);
}
```

- [ ] **Step 2: 运行测试并确认 RED**

```powershell
mvn -q -pl admin -Dtest=AgentPendingActionFacadeServiceTest test
```

Expected: FAIL。

- [ ] **Step 3: 实现 trusted user 和 gid guard**

实现方式与 `RiskCenterFacadeServiceImpl` 保持一致：

```java
Long groupCount = groupService.count(Wrappers.lambdaQuery(GroupDO.class)
        .eq(GroupDO::getUsername, UserContext.getUsername())
        .eq(GroupDO::getGid, gid)
        .eq(GroupDO::getDelFlag, 0));
```

Facade 对 list/detail/confirm/reject 都要求明确 gid。Detail 请求必须接收 expectedGid，不能先远程读取动作再信任返回 gid。

`AgentActionAdminErrorCode` 至少定义 `ACTION_SCOPE_FORBIDDEN` 和 `ACTION_REQUEST_INVALID`。gid 不属于当前用户时抛 `ClientException(message, AgentActionAdminErrorCode.ACTION_SCOPE_FORBIDDEN)`，使局部 Controller advice 可以稳定返回 403，而不是被全局 handler 降为普通 200 错误体。

- [ ] **Step 4: 限制分页和请求体**

```text
pageNo 最小 1。
pageSize 默认 10，最大 100。
confirm/reject request 为空时创建空 DTO 后校验 expectedGid。
body 中不存在 username/reviewer/confirmedBy/rejectedBy 字段。
```

- [ ] **Step 5: 运行测试并提交**

```powershell
mvn -q -pl admin -Dtest=AgentPendingActionFacadeServiceTest test
git add admin/src/main/java/com/nageoffer/shortlink/admin/common/convention/errorcode/AgentActionAdminErrorCode.java admin/src/main/java/com/nageoffer/shortlink/admin/service admin/src/test/java/com/nageoffer/shortlink/admin/service/AgentPendingActionFacadeServiceTest.java
git commit -m "feat: guard pending actions by gid ownership"
```

## Task 4: 实现 Admin Public Controller

**Files:**

- Create: `admin/src/main/java/com/nageoffer/shortlink/admin/controller/AgentPendingActionController.java`
- Create: `admin/src/main/java/com/nageoffer/shortlink/admin/controller/AgentPendingActionExceptionHandler.java`
- Create: `admin/src/test/java/com/nageoffer/shortlink/admin/controller/AgentPendingActionControllerTest.java`

- [ ] **Step 1: 写 MockMvc 合同失败测试**

```java
@Test
void confirmEndpointReturnsActionView() throws Exception {
    AgentPendingActionRespDTO response = new AgentPendingActionRespDTO();
    response.setActionId("action-1");
    response.setStatus("EXECUTED");
    when(facade.confirm(eq("action-1"), any())).thenReturn(Results.success(response));

    mockMvc.perform(post("/api/short-link/admin/v1/agent/actions/action-1/confirm")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("{\"expectedGid\":\"g1\",\"expectedVersion\":2,\"note\":\"confirm\"}"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.actionId").value("action-1"))
            .andExpect(jsonPath("$.data.status").value("EXECUTED"));
}
```

补充 list/detail/reject endpoint 和无认证 UserContext 的异常映射测试。

补充远程错误与执行中状态：

```java
@Test
void preservesRemoteConflictStatusAndCode() throws Exception {
    when(facade.confirm(eq("action-1"), any()))
            .thenThrow(new AgentActionRemoteException(
                    HttpStatus.CONFLICT,
                    "ACTION_VERSION_CONFLICT",
                    "expectedVersion is stale"
            ));

    mockMvc.perform(post("/api/short-link/admin/v1/agent/actions/action-1/confirm")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("{\"expectedGid\":\"g1\",\"expectedVersion\":1}"))
            .andExpect(status().isConflict())
            .andExpect(jsonPath("$.code").value("ACTION_VERSION_CONFLICT"));
}
```

另测 remote 403、500，以及 Facade 返回 `ACTION_EXECUTING` 时 public API 返回 202。

- [ ] **Step 2: 运行测试并确认 RED**

```powershell
mvn -q -pl admin -Dtest=AgentPendingActionControllerTest test
```

Expected: FAIL。

- [ ] **Step 3: 实现 Controller**

```text
GET  /api/short-link/admin/v1/agent/actions
GET  /api/short-link/admin/v1/agent/actions/{actionId}?gid=g1
POST /api/short-link/admin/v1/agent/actions/{actionId}/confirm
POST /api/short-link/admin/v1/agent/actions/{actionId}/reject
```

Controller 只转发 Facade，不读取或修改 `UserContext`。

`AgentPendingActionExceptionHandler` 使用 `@RestControllerAdvice(assignableTypes = AgentPendingActionController.class)`：把 `AgentActionRemoteException.status/code/message` 转成 `ResponseEntity<Result<Void>>`；把本域 `ClientException` 的 `ACTION_SCOPE_FORBIDDEN` 映射为 403，`ACTION_REQUEST_INVALID` 映射为 400。Controller 对正常 Result 返回 200；Result code 为 `ACTION_EXECUTING` 时返回 202。其他 admin 全局异常行为不受影响。

- [ ] **Step 4: 运行测试并提交**

```powershell
mvn -q -pl admin -Dtest=AgentPendingActionControllerTest,AgentPendingActionFacadeServiceTest,AgentPendingActionRemoteServiceTest test
git add admin/src/main/java/com/nageoffer/shortlink/admin/controller/AgentPendingActionController.java admin/src/main/java/com/nageoffer/shortlink/admin/controller/AgentPendingActionExceptionHandler.java admin/src/test/java/com/nageoffer/shortlink/admin/controller/AgentPendingActionControllerTest.java
git commit -m "feat: expose admin pending action api"
```

## Task 5: 将待确认动作接入现有 Agent 管理页面

**Files:**

- Modify: `admin/src/main/resources/static/agent-admin/index.html`
- Modify: `admin/src/test/java/com/nageoffer/shortlink/admin/controller/AgentAdminPageStaticResourceTest.java`

- [ ] **Step 1: 写静态页面失败合同**

```java
@Test
void adminPageUsesFormalPendingActionApisAndNeverCallsInternalEndpoints() throws Exception {
    String html = mockMvc.perform(get("/agent-admin/index.html"))
            .andExpect(status().isOk())
            .andReturn()
            .getResponse()
            .getContentAsString(StandardCharsets.UTF_8);

    assertThat(html)
            .contains("/api/short-link/admin/v1/agent/actions")
            .contains("PENDING")
            .contains("RETRY_WAIT")
            .contains("确认处置")
            .contains("拒绝建议")
            .doesNotContain("/internal/short-link-agent/")
            .doesNotContain("executionToken")
            .doesNotContain("payloadJson");
}
```

- [ ] **Step 2: 运行测试并确认 RED**

```powershell
mvn -q -pl admin -Dtest=AgentAdminPageStaticResourceTest test
```

Expected: FAIL。

- [ ] **Step 3: 实现页面状态和请求函数**

新增 JavaScript 状态：

```text
actionFilters
actionPage
selectedAction
actionLoading
actionSubmitting
```

新增函数：

```text
loadPendingActions()
openActionDetail(actionId, gid)
confirmAction(actionId, gid, version, note)
rejectAction(actionId, gid, version, reason, reviewAction)
renderActionStatus(status, syncStatus)
```

所有文本通过现有转义函数输出；失败信息不直接插入 `innerHTML`。

- [ ] **Step 4: 实现稳定布局**

```text
列表使用 table/list，不嵌套卡片。
状态 badge 固定最小宽度。
按钮区固定，不因 loading 文案改变布局。
移动端将操作按钮换行，不覆盖摘要。
确认/拒绝对话框使用原生 dialog 或现有 modal 模式。
```

- [ ] **Step 5: 运行页面合同测试**

```powershell
mvn -q -pl admin -Dtest=AgentAdminPageStaticResourceTest,AgentPendingActionControllerTest test
```

Expected: PASS。

- [ ] **Step 6: 提交页面**

```powershell
git add admin/src/main/resources/static/agent-admin/index.html admin/src/test/java/com/nageoffer/shortlink/admin/controller/AgentAdminPageStaticResourceTest.java
git commit -m "feat: add pending actions to agent admin"
```

## Task 6: 02D 阶段验证和推送

**Files:**

- Modify: `plan/Agent平台增强/02_Agent生产就绪增强实施计划/02_待确认动作与策略一致性/00_总控索引.md`

- [ ] **Step 1: 运行 admin 全量测试**

```powershell
mvn -q -pl admin test
```

Expected: PASS。

- [ ] **Step 2: 运行 agent-service API 回归**

```powershell
mvn -q -pl agent-service -Dtest=AgentPendingActionInternalControllerTest,AgentPendingActionServiceTest,RiskPolicyActionExecutorTest test
```

Expected: PASS。

- [ ] **Step 3: 更新总控状态和 Figma URL**

将 02D 标记为“已完成”，记录 Figma URL、admin 测试数量和 internal API 回归结果。

- [ ] **Step 4: 推送阶段**

```powershell
git add plan/Agent平台增强/02_Agent生产就绪增强实施计划
git commit -m "docs: complete admin action confirmation phase"
git push origin codex/pending-action-policy-consistency
```
