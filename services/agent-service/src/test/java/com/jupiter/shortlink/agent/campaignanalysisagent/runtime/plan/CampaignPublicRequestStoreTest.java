package com.jupiter.shortlink.agent.campaignanalysisagent.runtime.plan;

import static org.junit.jupiter.api.Assertions.*;
import com.jupiter.shortlink.agent.campaignanalysisagent.planning.NativeCampaignPlanner;
import com.jupiter.shortlink.agent.campaignanalysisagent.runtime.capacity.ProcessExecutionScope;
import com.jupiter.shortlink.agent.campaignanalysisagent.runtime.model.ModelInvocationRegistry;
import com.jupiter.shortlink.agent.campaignanalysisagent.runtime.persistence.CampaignRunStore.Caller;
import com.jupiter.shortlink.agent.infrastructure.config.DeepSeekProperties;
import com.jupiter.shortlink.agent.infrastructure.llm.DeepSeekSpringAiChatModel;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.model.*;
import org.springframework.ai.chat.prompt.ChatOptions;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.model.tool.ToolCallingChatOptions;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.*;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestTemplate;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.*;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

class CampaignPublicRequestStoreTest {
    private static final Instant NOW=Instant.parse("2026-09-23T00:00:00Z");
    private static final Caller OWNER=new Caller("1001","analyst",7);
    private CampaignPublicRequestStore store() {
        var ds=new DriverManagerDataSource("jdbc:h2:mem:public-"+UUID.randomUUID()+";MODE=MySQL;DB_CLOSE_DELAY=-1","sa","");
        new ResourceDatabasePopulator(new ClassPathResource("sql/migration/V20260924_3__campaign_public_request.sql"),
                new ClassPathResource("sql/migration/V20260924_6__campaign_public_request_cancellation.sql")).execute(ds);
        return new CampaignPublicRequestStore(new JdbcTemplate(ds),new TransactionTemplate(new DataSourceTransactionManager(ds)),
                Clock.fixed(NOW,ZoneOffset.UTC));
    }

    @Test void nativeInterpretationIsDurableAndDuplicateOrUnknownRequestsCannotRedispatch() throws Exception {
        var store=store(); var request=store.register(OWNER,"session","once","比较访问",NOW.plusSeconds(3600));
        assertEquals(request.expiresAt(),store.register(OWNER,"session","once","比较访问",NOW.plusSeconds(7200)).expiresAt());
        assertThrows(IllegalArgumentException.class,()->store.register(OWNER,"session","once","不同问题",NOW.plusSeconds(3600)));
        AtomicInteger calls=new AtomicInteger();
        ChatModel model=new ChatModel() {
            public ChatResponse call(Prompt prompt) {
                calls.incrementAndGet(); return new ChatResponse(List.of(new Generation(new AssistantMessage(candidate("比较访问",4)))));
            }
            public ChatOptions getDefaultOptions() { return ToolCallingChatOptions.builder().model("fixture").build(); }
        };
        var scope=new ProcessExecutionScope(); String[] attempt={null};
        String schema=CampaignInterpretedRequest.schemaJson();
        try {
            String answer=new NativeCampaignPlanner(model,(actual,live)-> {
                assertTrue(actual.tools().isEmpty());
                attempt[0]=store.begin(request,"a".repeat(64));
                var response=live.get(); store.complete(request,attempt[0],response.text()); return response;
            },scope,ModelInvocationRegistry.Limits.defaults()).generateStructured(
                    CampaignInterpretedRequest.instructions(),"比较访问\n"+schema,schema);
            assertEquals("比较访问",CampaignInterpretedRequest.parse(answer,"比较访问").question());
            assertTrue(store.read(request.reference()).callbackActive());
        } finally { store.callbackExited(request,attempt[0]); scope.closeAndAwaitActualExit(); }
        assertEquals(1,calls.get());
        assertEquals("READY",store.read(request.reference()).state());
        assertThrows(IllegalStateException.class,()->store.begin(request,"a".repeat(64)));
        var unknown=store.register(OWNER,"session","unknown","比较访问",NOW.plusSeconds(3600));
        String unknownAttempt=store.begin(unknown,"b".repeat(64));
        store.unknown(unknown,unknownAttempt); store.callbackExited(unknown,unknownAttempt);
        assertThrows(IllegalStateException.class,()->store.begin(unknown,"b".repeat(64)));
        assertEquals("UNKNOWN",store.read(unknown.reference()).state());
    }

    @Test void requirementsCannotDropQuestionSpansInventQueryKindsOrRewriteOriginalRequest() {
        assertEquals(1,CampaignInterpretedRequest.parse(candidate("分析趋势",4),"分析趋势").goals().size());
        assertThrows(IllegalArgumentException.class,()->CampaignInterpretedRequest.parse(candidate("分析趋势和排名",4),"分析趋势和排名"));
        assertThrows(IllegalArgumentException.class,()->CampaignInterpretedRequest.parse(candidate("分析趋势",4),"分析排名"));
        assertThrows(IllegalArgumentException.class,()->CampaignInterpretedRequest.parse(candidate("分析趋势",4).replace("METRICS","REVENUE"),"分析趋势"));
        assertThrows(IllegalArgumentException.class,()->CampaignInterpretedRequest.parse(candidate("分析趋势",4)+" {}","分析趋势"));
    }

    @Test void productionModelDefaultsReachTransportOnlyAfterDurableDispatchRegistration() throws Exception {
        var store=store();
        var request=store.register(OWNER,"session","production-options","比较访问",NOW.plusSeconds(3600));
        var properties=new DeepSeekProperties();
        properties.setApiKey("fixture-key");
        var rest=new RestTemplate();
        var server=MockRestServiceServer.createServer(rest);
        var model=new DeepSeekSpringAiChatModel(properties,rest);
        String response=new ObjectMapper().writeValueAsString(Map.of("id","fixture-response","model",properties.getModel(),
                "choices",List.of(Map.of("finish_reason","stop","message",Map.of("role","assistant","content",candidate("比较访问",4))))));
        server.expect(requestTo("https://api.deepseek.com/chat/completions"))
                .andExpect(jsonPath("$.model").value(properties.getModel()))
                .andExpect(jsonPath("$.max_tokens").value(2000))
                .andExpect(ignored->{
                    var persisted=store.read(request.reference());
                    assertEquals("DISPATCHING",persisted.state());
                    assertTrue(persisted.callbackActive());
                }).andRespond(withSuccess(response,MediaType.APPLICATION_JSON));
        var scope=new ProcessExecutionScope();
        String[] attempt={null};
        try {
            String schema=CampaignInterpretedRequest.schemaJson();
            String answer=new NativeCampaignPlanner(model,(actual,live)->{
                assertTrue(actual.tools().isEmpty());
                assertEquals(properties.getModel(),actual.generationOptions().model());
                assertEquals(2000,actual.generationOptions().maxTokens());
                attempt[0]=store.begin(request,com.jupiter.shortlink.agent.campaignanalysisagent.runtime.persistence.CampaignRunStore
                        .sha256(ModelInvocationRegistry.encodeRequest(actual)));
                var accepted=live.get();
                store.complete(request,attempt[0],accepted.text());
                return accepted;
            },scope,ModelInvocationRegistry.Limits.defaults()).generateStructured(
                    CampaignInterpretedRequest.instructions(),"比较访问\n"+schema,schema);
            assertEquals("比较访问",CampaignInterpretedRequest.parse(answer,"比较访问").question());
        } finally {
            if (attempt[0]!=null) store.callbackExited(request,attempt[0]);
            scope.closeAndAwaitActualExit();
        }
        server.verify();
        assertEquals("READY",store.read(request.reference()).state());
        assertFalse(store.read(request.reference()).callbackActive());
    }

    private static String candidate(String question,int end) {
        return """
            {"schemaVersion":"campaign-requirements/v1","question":"%s","clarification":[],"goals":[{
            "question":"访问趋势","sourceStart":0,"sourceEnd":%d,"method":"STATISTICS","metric":"PV","causal":false,"dependsOn":[],"needsAnalysis":false,"needsRecommendation":false,
            "queries":[{"gid":"g1","fullShortUrl":null,"period":{"startDate":"2026-09-01","endDate":"2026-09-07"},"queryKind":"METRICS","dimensions":[],"filters":[]}]}]}
            """.formatted(question,end);
    }
}
