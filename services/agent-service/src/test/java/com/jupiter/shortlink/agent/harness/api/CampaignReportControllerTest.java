package com.jupiter.shortlink.agent.harness.api;

import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jupiter.shortlink.agent.campaignanalysisagent.runtime.persistence.CampaignDeclineSelectionStore;
import com.jupiter.shortlink.agent.campaignanalysisagent.runtime.persistence.CampaignRunStore;
import com.jupiter.shortlink.agent.campaignanalysisagent.runtime.persistence.CampaignRunStore.*;
import com.jupiter.shortlink.agent.campaignanalysisagent.runtime.persistence.CampaignStatisticsResultStore;
import com.jupiter.shortlink.agent.campaignanalysisagent.runtime.persistence.CampaignStepStore;
import com.jupiter.shortlink.agent.campaignanalysisagent.runtime.report.CampaignArtifactReportRows;
import com.jupiter.shortlink.agent.campaignanalysisagent.runtime.report.CampaignReportDeliveryService;
import com.jupiter.shortlink.agent.campaignanalysisagent.runtime.report.JdbcReportLifecycleStore;
import java.time.Clock;
import java.util.List;
import java.util.Optional;
import javax.sql.DataSource;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.transaction.support.TransactionTemplate;

class CampaignReportControllerTest {
    private static final Caller OWNER=new Caller("1001","analyst",7);
    private static final String BASE="/internal/short-link-agent/v1/campaign/reports/report/revisions/1";
    private static final List<String> PATHS=List.of(BASE,BASE+"/blocks/table/rows",BASE+"/export");
    private static final String DENIED="{\"success\":false,\"code\":\"RESOURCE_UNAVAILABLE\"}";

    @Test
    void revokedReportAccessReturnsGeneric403ForReadRowsAndExportBeforeAnyPayloadRead() throws Exception {
        var f=new Fixture();
        var definition=new RunDefinition(OWNER,"session","run","plan",1,"{}");
        when(f.runs.loadRun(OWNER,"run")).thenReturn(Optional.of(new RunRecord(definition,RunStatus.ACTIVE,1,"token")));
        for (String path:PATHS) {
            f.mvc.perform(request(path,"1001","analyst","7"))
                    .andExpect(status().isForbidden())
                    .andExpect(content().json(DENIED,true));
        }
        verify(f.runs,times(6)).loadRun(OWNER,"run");
        verifyNoMoreInteractions(f.runs);
        verifyNoInteractions(f.steps,f.statistics,f.selections);
        verify(f.source,never()).getConnection();
    }

    @Test
    void malformedCallerReturnsTheSame403WithoutLookingUpAnyRun() throws Exception {
        var f=new Fixture();
        for (String path:PATHS) {
            for (List<String> identity:List.of(List.of("1001"," ","7"),List.of(" ","analyst","7"),
                    List.of("1001","analyst","0"))) {
                f.mvc.perform(request(path,identity.get(0),identity.get(1),identity.get(2)))
                        .andExpect(status().isForbidden())
                        .andExpect(content().json(DENIED,true));
            }
        }
        verifyNoInteractions(f.runs,f.steps,f.statistics,f.selections);
        verify(f.source,never()).getConnection();
    }

    private static MockHttpServletRequestBuilder request(String path,String tenant,String username,String authVersion) {
        return get(path).param("sessionId","session").param("runId","run").param("planId","plan").param("planRevision","1")
                .header("X-Agent-UserId",tenant).header("X-Agent-Username",username).header("X-Agent-Auth-Version",authVersion);
    }

    private static final class Fixture {
        final DataSource source=mock(DataSource.class);
        final CampaignRunStore runs=mock(CampaignRunStore.class);
        final CampaignStepStore steps=mock(CampaignStepStore.class);
        final CampaignStatisticsResultStore statistics=mock(CampaignStatisticsResultStore.class);
        final CampaignDeclineSelectionStore selections=mock(CampaignDeclineSelectionStore.class);
        final MockMvc mvc;
        Fixture() {
            var jdbc=new JdbcTemplate(source);
            var tx=new TransactionTemplate(new DataSourceTransactionManager(source));
            var clock=Clock.systemUTC();
            var lifecycle=new JdbcReportLifecycleStore(jdbc,tx,clock);
            var service=new CampaignReportDeliveryService(jdbc,tx,clock,runs,steps,lifecycle,
                    (caller,artifact)->false,(caller,definition)->false,
                    new CampaignArtifactReportRows(runs,statistics,selections));
            mvc=MockMvcBuilders.standaloneSetup(new CampaignReportController(service)).build();
        }
    }
}
