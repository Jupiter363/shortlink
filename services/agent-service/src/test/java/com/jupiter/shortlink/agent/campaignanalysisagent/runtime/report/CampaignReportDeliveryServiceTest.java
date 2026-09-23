package com.jupiter.shortlink.agent.campaignanalysisagent.runtime.report;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import com.jupiter.shortlink.agent.campaignanalysisagent.planning.*;
import com.jupiter.shortlink.agent.campaignanalysisagent.report.*;
import com.jupiter.shortlink.agent.campaignanalysisagent.report.CampaignReportPublisher.ReportRef;
import com.jupiter.shortlink.agent.campaignanalysisagent.runtime.persistence.*;
import com.jupiter.shortlink.agent.campaignanalysisagent.runtime.persistence.CampaignRunStore.*;
import com.jupiter.shortlink.agent.campaignanalysisagent.runtime.plan.FrozenCampaignRun;
import com.jupiter.shortlink.agent.business.shortlink.AgentAuthorityClient;
import com.jupiter.shortlink.agent.harness.security.AgentPrincipal;
import java.time.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicBoolean;
import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;
import org.springframework.transaction.support.TransactionTemplate;

class CampaignReportDeliveryServiceTest {
    private static final Instant NOW = Instant.parse("2026-09-23T00:00:00Z");
    private static final Clock CLOCK = Clock.fixed(NOW, ZoneOffset.UTC);

    @Test
    void exactReportHistoryRowsAndExportShareStoredBlocksAndOriginalGoalOrder() {
        Fixture f = fixture();
        var view = f.service.read(f.caller, f.reference, f.ref);
        assertThat(view.modules()).extracting(CampaignReportView.Module::goalId).containsExactly("goal-z", "goal-a");
        assertThat(view.modules().get(0).blockIds()).containsExactly("table", "result", "analysis");
        assertThat(view.blocksById()).hasSize(3);
        String cursor = (String) view.blocksById().get("table").payload().get("nextCursor");
        var rows = f.service.rows(f.caller, f.reference, f.ref, "table", cursor, 1);
        assertThat(rows.rows()).containsExactly(Map.of("pv", 9));
        assertThat(rows.nextCursor()).isNull();
        assertThatThrownBy(() -> f.service.rows(f.caller, f.reference, f.ref, "result", cursor, 1))
                .hasMessage("REPORT_CURSOR_INVALID");
        assertThat(f.service.history(f.caller, "session", null, 20).items()).hasSize(1);
        var export = f.service.export(f.caller, f.reference, f.ref);
        assertThat(export.view()).isEqualTo(view);
        assertThat(export.content()).contains("完整分析内容，不做截断", "\"pv\":12", "完整结果共 2 行", "固定报告版本分页读取");
        assertThat(f.jdbc.queryForObject("SELECT COUNT(*) FROM campaign_report_lifecycle", Integer.class)).isEqualTo(1);
        verifyNoInteractions(f.steps);
    }

    @Test
    void exactIdentityAndCurrentAclAreRequiredForEveryReadAndPage() {
        Fixture f = fixture();
        assertThatThrownBy(() -> f.service.read(f.caller,
                new CampaignReportDeliveryService.Reference("other-session", "run", "plan", 1), f.ref))
                .hasMessage("REPORT_REFERENCE_MISMATCH");
        f.allowed.set(false);
        assertThatThrownBy(() -> f.service.read(f.caller, f.reference, f.ref)).isInstanceOf(SecurityException.class);
        assertThatThrownBy(() -> f.service.rows(f.caller, f.reference, f.ref, "table", null, 25)).isInstanceOf(SecurityException.class);
        assertThatThrownBy(() -> f.service.export(f.caller, f.reference, f.ref)).isInstanceOf(SecurityException.class);
        assertThat(f.service.history(f.caller, "session", null, 20).items()).isEmpty();
        assertThat(f.jdbc.queryForObject("SELECT COUNT(*) FROM campaign_report_lifecycle", Integer.class)).isEqualTo(1);
    }

    @Test
    void successfulFrozenStepIsAssembledPublishedBoundAndReusedWithoutAnotherRevision() {
        Fixture f = fixture(false);
        var first = f.service.publishCurrent(f.caller, f.token).orElseThrow();
        assertThat(first.availability()).isEqualTo(CampaignLegacyAnswerAdapter.Availability.COMPLETE);
        assertThat(first.view().goalAssessments()).allMatch(goal -> goal.status() == GoalAssessment.Status.ANSWERED);
        assertThat(first.view().blocksById().values()).extracting(ReportBlock::kind)
                .contains(ReportBlock.Kind.METRIC, ReportBlock.Kind.ANALYSIS, ReportBlock.Kind.TABLE, ReportBlock.Kind.RESULT_LINK);
        assertThat(first.view().blocksById().values()).filteredOn(block -> block.kind() == ReportBlock.Kind.ANALYSIS)
                .allMatch(block -> block.text().contains("2026-09-01 至 2026-09-02") && block.text().contains("分组 alpha"));
        var second = f.service.publishCurrent(f.caller, f.token).orElseThrow();
        assertThat(second).isEqualTo(first);
        assertThat(f.service.latest(f.caller, f.token)).contains(first);
        assertThat(f.jdbc.queryForObject("SELECT COUNT(*) FROM campaign_report_lifecycle", Integer.class)).isEqualTo(1);
        assertThat(f.jdbc.queryForObject("SELECT COUNT(*) FROM campaign_run_result_binding", Integer.class)).isEqualTo(1);
        assertThat(f.jdbc.queryForObject("SELECT reference_count FROM campaign_report_lifecycle", Integer.class)).isEqualTo(1);
    }

    @Test
    void sealedHistoryAndCompleteRowsRemainOnOriginalRevisionAfterReplanButRespectCurrentAcl() {
        Fixture f=fixture(true,true);
        var before=f.service.read(f.caller,f.reference,f.ref);
        var original=FrozenCampaignRun.read(f.token.definition());
        assertThat(CampaignSealedReportAccess.frozenJson(original)).isEqualTo(f.token.definition().definitionJson());
        var plan=new PlanSpec(PlanSpec.SCHEMA_VERSION,"plan",2,"run","inputs",original.plan().goals(),original.plan().steps());
        var assessment=new PlanningAssessment("plan",2,"catalog",original.assessment().requirements(),
                original.assessment().coverageBindings(),original.assessment().gaps());
        var revised=FrozenCampaignRun.freeze(plan,original.inputs(),assessment).definition(f.caller,"session");
        f.realRuns.revise(f.token,2,revised.definitionJson());
        assertThat(f.service.read(f.caller,f.reference,f.ref)).isEqualTo(before);
        assertThat(f.service.export(f.caller,f.reference,f.ref).view()).isEqualTo(before);
        assertThat(f.service.history(f.caller,"session",null,20).items()).extracting(CampaignReportDeliveryService.HistoryItem::planRevision)
                .containsExactly(1);
        var first=f.service.rows(f.caller,f.reference,f.ref,"result",null,1);
        var second=f.service.rows(f.caller,f.reference,f.ref,"result",first.nextCursor(),1);
        assertThat(first.rows()).containsExactly(Map.of("pv",12));assertThat(second.rows()).containsExactly(Map.of("pv",9));
        assertThat(second.nextCursor()).isNull();
        assertThatThrownBy(()->f.service.publishCurrent(f.caller,f.token)).isInstanceOf(SecurityException.class);
        f.allowed.set(false);
        assertThatThrownBy(()->f.service.rows(f.caller,f.reference,f.ref,"result",null,1)).isInstanceOf(SecurityException.class);
        assertThat(f.service.history(f.caller,"session",null,20).items()).isEmpty();
        assertThat(f.jdbc.queryForObject("SELECT COUNT(*) FROM campaign_report_lifecycle",Integer.class)).isEqualTo(1);
        f.allowed.set(true);
        f.jdbc.update("UPDATE campaign_report_lifecycle SET retained_until=?",NOW.toEpochMilli());
        assertThat(f.service.history(f.caller,"session",null,20).items()).isEmpty();
    }

    @Test
    void currentReportCanUseOlderStatisticsOnlyAfterCurrentArtifactGateApprovesThem() {
        Fixture f=fixture(false,true);
        var original=FrozenCampaignRun.read(f.token.definition());
        var plan=new PlanSpec(PlanSpec.SCHEMA_VERSION,"plan",2,"run","inputs",original.plan().goals(),original.plan().steps());
        var assessment=new PlanningAssessment("plan",2,"catalog",original.assessment().requirements(),
                original.assessment().coverageBindings(),original.assessment().gaps());
        var current=f.realRuns.revise(f.token,2,FrozenCampaignRun.freeze(plan,original.inputs(),assessment).definition(f.caller,"session").definitionJson());
        var completed=f.steps.steps(f.token);
        when(f.steps.steps(current)).thenReturn(completed);
        var report=f.service.publishCurrent(f.caller,current).orElseThrow();
        assertThat(report.view().planRevision()).isEqualTo(2);
        assertThat(report.view().goalAssessments()).allMatch(goal->goal.status()==GoalAssessment.Status.ANSWERED);
        assertThat(report.view().blocksById().values()).flatExtracting(ReportBlock::evidenceArtifactIds).contains("artifact");
        f.allowed.set(false);
        assertThatThrownBy(()->f.service.read(f.caller,CampaignReportDeliveryService.Reference.of(current.definition()),report.reportRef()))
                .isInstanceOf(SecurityException.class);
    }

    private static Fixture fixture() { return fixture(true); }
    private static Fixture fixture(boolean publishFixture) {return fixture(publishFixture,false);}
    private static Fixture fixture(boolean publishFixture,boolean historical) {
        JdbcDataSource source = new JdbcDataSource();
        source.setURL("jdbc:h2:mem:report_delivery_" + UUID.randomUUID() + ";MODE=MySQL;DB_CLOSE_DELAY=-1");
        new ResourceDatabasePopulator(new ClassPathResource("sql/migration/V20260919__campaign_run_ledger.sql"),
                new ClassPathResource("sql/migration/V20260920_22__campaign_report_lifecycle.sql"),
                new ClassPathResource("sql/migration/V20260921__campaign_run_result_binding.sql")).execute(source);
        JdbcTemplate jdbc = new JdbcTemplate(source);
        TransactionTemplate tx = new TransactionTemplate(new DataSourceTransactionManager(source));
        Caller caller = new Caller("1001", "subject", 1);
        List<PlanSpec.Goal> goals = List.of(new PlanSpec.Goal("goal-z", "首先回答", true, "数据"), new PlanSpec.Goal("goal-a", "然后回答", true, "分析"));
        var queryStep = new PlanSpec.Step("query", List.of("goal-z", "goal-a"), PlanSpec.ExecutionMode.FIXED,
                new PlanSpec.ExecutorRef(PlanSpec.ExecutorKind.TOOL, "statistics_query_job", "1"), null,
                List.of(), Map.of(), Map.of(), "StatisticsJobPages");
        var plan = new PlanSpec(PlanSpec.SCHEMA_VERSION, "plan", 1, "run", "inputs", goals, List.of(queryStep));
        var assessment = new PlanningAssessment("plan", 1, "catalog", goals.stream().map(goal -> new PlanningAssessment.Requirement(
                goal.goalId() + "-delivery", goal.goalId(), PlanningAssessment.RequirementKind.DELIVERY, true, "delivery", "1", Map.of())).toList(),
                goals.stream().map(goal -> new PlanningAssessment.CoverageBinding(goal.goalId() + "-delivery",
                        List.of(new PlanningAssessment.EvidenceOutput("query", "result")))).toList(), List.of());
        RunDefinition definition = FrozenCampaignRun.freeze(plan, new FrozenInputSet("inputs", "run", Map.of(), Map.of()), assessment)
                .definition(caller, "session");
        JdbcCampaignRunStore realRuns = new JdbcCampaignRunStore(jdbc, tx, CLOCK);
        RunToken token = realRuns.createRun(definition);
        var ref = new ReportRef("report", 1);
        AtomicBoolean allowed = new AtomicBoolean(true);
        ArtifactAuthorizer gate = (user, artifact) -> allowed.get() && user.equals(artifact.owner());
        String body = "{\"resultComplete\":true,\"totalRows\":2,\"receivedPageCount\":1,\"metrics\":{\"requested\":{\"pv\":21}}}";
        var metadata = new ArtifactMetadata(new ArtifactRef("artifact", "StatisticsJobPages", "statistics-job-pages/v1",
                CampaignRunStore.sha256(body), "scope", "period", NOW.plusSeconds(3600)), caller,
                "run", "plan", 1, "action", "child", "1", "{}", "{}");
        CampaignRunStore runs = mock(CampaignRunStore.class);
        when(runs.loadRun(eq(caller), eq("run"))).thenAnswer(call -> realRuns.loadRun(caller, "run"));
        when(runs.child(any(RunToken.class), eq("child"))).thenReturn(Optional.of(new ChildRecord(new ChildSpec("child", "action", ChildMode.ASYNC,
                "request", new WireRequest("POST", "/statistics", "{\"gid\":\"alpha\",\"startDate\":\"2026-09-01\",\"endDate\":\"2026-09-02\"}")),
                ChildState.READY, "job", "artifact", null, 1, null, false, null)));
        when(runs.inspectAction(any(RunToken.class),eq("action"))).thenReturn(Optional.of(new ActionSpec("action","query","TOOL",
                "statistics_query_job","1",CampaignSealedReportAccess.frozenJson(queryStep))));
        when(runs.inspectArtifact(eq(caller), eq("artifact"), any())).thenAnswer(call -> {
            if (!call.<ArtifactAuthorizer>getArgument(2).mayRead(caller, metadata)) throw new SecurityException("DENIED");
            return metadata;
        });
        when(runs.readArtifact(eq(caller), eq("artifact"), any())).thenAnswer(call -> {
            if (!call.<ArtifactAuthorizer>getArgument(2).mayRead(caller, metadata)) throw new SecurityException("DENIED");
            return new Artifact(metadata, body);
        });
        CampaignStatisticsResultStore statistics = mock(CampaignStatisticsResultStore.class);
        when(statistics.readPage(eq(caller), eq("artifact"), eq(0), any())).thenReturn("{\"items\":[{\"pv\":12},{\"pv\":9}]}");
        CampaignStepStore steps = mock(CampaignStepStore.class);
        when(steps.steps(token)).thenReturn(List.of(new CampaignStepStore.StepRecord(
                new CampaignStepStore.StepSpec("query", "{}", List.of(), Set.of("result"), Set.of("result")),
                CampaignStepStore.StepStatus.SUCCEEDED, 1, null, false, null, Map.of("result", "artifact"))));
        var lifecycle = new JdbcReportLifecycleStore(jdbc, tx, CLOCK);
        var publisher = new CampaignReportPublisher(new GoalAssessor(), id -> Optional.of(new CampaignReportPublisher.Evidence(
                "artifact", metadata.ref().type(), metadata.ref().schemaVersion(), metadata.ref().payloadHash(),
                metadata.ref().expiresAt(), "campaign-report-rows/v1", true)), CLOCK, CampaignReportDeliveryService.CLIENT_CAPABILITY, lifecycle);
        List<ReportBlock> blocks = List.of(new ReportBlock("table", ReportBlock.Kind.TABLE, "访问明细", null,
                Map.of("artifactId", "artifact", "columns", List.of(Map.of("key", "pv", "label", "访问量")),
                        "rows", List.of(Map.of("pv", 12)), "nextCursor", "1", "totalRows", 2), List.of("artifact"), false),
                new ReportBlock("result", ReportBlock.Kind.RESULT_LINK, "完整结果", "全部结果", Map.of("artifactId", "artifact"), List.of("artifact"), true),
                new ReportBlock("analysis", ReportBlock.Kind.ANALYSIS, "分析", "完整分析内容，不做截断", Map.of("claimType", "OBSERVED_ONLY"), List.of("artifact"), false));
        var draft = new ReportDraft("report", 1, "run", "plan", 1, List.of(
                new ReportSection("z", 0, "首先回答", List.of("goal-z"), blocks),
                new ReportSection("a", 1, "然后回答", List.of("goal-a"), blocks)), List.of());
        if (publishFixture) publisher.publishDurable(new CampaignReportPublisher.PublishRequest(plan, assessment, Map.of(), draft,
                CampaignReportDeliveryService.CLIENT_CAPABILITY), new CampaignReportPublisher.Publication(
                CampaignReportDeliveryService.owner(caller), CampaignReportDeliveryService.capability(definition),
                NOW.plusSeconds(3600), NOW.plusSeconds(3600)));
        CampaignReportDeliveryService.RunAccess access=(user,run)->allowed.get()&&caller.equals(user);
        var authority=mock(AgentAuthorityClient.class);
        when(authority.verifyCurrentPrincipal(any())).thenAnswer(call->call.getArgument(0));
        var service = new CampaignReportDeliveryService(jdbc, tx, CLOCK, runs, steps, lifecycle, gate,access,
                new CampaignArtifactReportRows(runs, statistics, mock(CampaignDeclineSelectionStore.class)),null,
                historical?new CampaignSealedReportAccess(jdbc,CLOCK,runs,authority,access):null);
        return new Fixture(jdbc, caller, token, CampaignReportDeliveryService.Reference.of(definition), ref, service, allowed, steps,realRuns);
    }
    private record Fixture(JdbcTemplate jdbc, Caller caller, RunToken token, CampaignReportDeliveryService.Reference reference,
            ReportRef ref, CampaignReportDeliveryService service, AtomicBoolean allowed, CampaignStepStore steps,JdbcCampaignRunStore realRuns) { }
}
