package com.jupiter.shortlink.agent.campaignanalysisagent.runtime.report;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.StreamReadFeature;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.MapperFeature;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.jupiter.shortlink.agent.business.shortlink.AgentAuthorityClient;
import com.jupiter.shortlink.agent.campaignanalysisagent.runtime.persistence.CampaignRunStore;
import com.jupiter.shortlink.agent.campaignanalysisagent.runtime.persistence.CampaignRunStore.*;
import com.jupiter.shortlink.agent.campaignanalysisagent.runtime.plan.FrozenCampaignRun;
import com.jupiter.shortlink.agent.campaignanalysisagent.runtime.plan.FrozenScopeCollection;
import com.jupiter.shortlink.agent.campaignanalysisagent.runtime.plan.FrozenStatisticsJobQuery;
import com.jupiter.shortlink.agent.campaignanalysisagent.runtime.plan.StatisticsJobFixedExecutor;
import com.jupiter.shortlink.agent.harness.security.AgentPrincipal;
import com.jupiter.shortlink.contract.GroupMembersPage;
import java.time.Clock;
import java.time.Instant;
import java.util.*;
import org.springframework.jdbc.core.JdbcTemplate;

/** Read-only authority derived from a sealed report, never authority to resume a superseded Run. */
public final class CampaignSealedReportAccess {
    // Keep the stored frozen JSON representation: sorted properties/maps, including nulls.
    private static final JsonMapper FROZEN_JSON=JsonMapper.builder()
            .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
            .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
            .enable(MapperFeature.SORT_PROPERTIES_ALPHABETICALLY)
            .enable(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS).build();
    private final JdbcTemplate jdbc;
    private final Clock clock;
    private final CampaignRunStore runs;
    private final AgentAuthorityClient authority;
    private final CampaignReportDeliveryService.RunAccess access;

    public CampaignSealedReportAccess(JdbcTemplate jdbc, Clock clock, CampaignRunStore runs,
            AgentAuthorityClient authority, CampaignReportDeliveryService.RunAccess access) {
        this.jdbc=Objects.requireNonNull(jdbc); this.clock=Objects.requireNonNull(clock);
        this.runs=Objects.requireNonNull(runs); this.authority=Objects.requireNonNull(authority);
        this.access=Objects.requireNonNull(access);
    }

    public RunToken token(Caller caller, CampaignReportDeliveryService.Reference reference) {
        var found=jdbc.query("SELECT * FROM campaign_run_ledger WHERE run_id=? AND revision=?",
                (rs,row)-> {
                    var definition=new RunDefinition(new Caller(rs.getString("tenant_id"),rs.getString("subject_name"),rs.getLong("auth_version")),
                            rs.getString("session_id"),rs.getString("run_id"),rs.getString("plan_id"),rs.getInt("revision"),rs.getString("definition_json"));
                    require(definition.definitionHash().equals(rs.getString("definition_hash")));
                    return new RunToken(definition,rs.getLong("row_version"),rs.getString("advance_token"));
                },reference.runId(),reference.planRevision());
        require(found.size()==1);
        RunToken token=found.get(0);
        require(caller.equals(token.definition().caller()) && reference.equals(CampaignReportDeliveryService.Reference.of(token.definition())));
        current(token);
        return token;
    }

    /** The lifecycle has already verified owner, capability, report payload and retention. */
    public Grant open(Caller caller, RunDefinition definition, ReportLifecycleStore.Published report) {
        RunToken token=token(caller,CampaignReportDeliveryService.Reference.of(definition));
        require(token.definition().equals(definition) && definition.runId().equals(report.runId())
                && definition.revision()==report.planRevision());
        JsonNode manifest=CampaignArtifactReportRows.tree(report.manifestJson());
        require(CampaignRunStore.sha256(report.manifestJson()).equals(report.manifestChecksum())
                && "campaign-evidence-manifest/v1".equals(manifest.path("schemaVersion").asText())
                && report.key().reportId().equals(manifest.path("reportId").asText())
                && report.key().revision()==manifest.path("revision").asInt()
                && manifest.path("entries").isArray() && !manifest.path("entries").isEmpty());
        Map<String,ArtifactMetadata> closure=new LinkedHashMap<>();
        Deque<ArtifactMetadata> pending=new ArrayDeque<>();
        Set<String> roots=new HashSet<>();
        for (JsonNode entry:manifest.path("entries")) {
            String id=entry.path("artifactId").asText(); require(roots.add(id));
            ArtifactMetadata root=runs.inspectArtifact(caller,id,(user,metadata)->sameLine(definition,metadata)
                    && caller.equals(user) && id.equals(metadata.ref().artifactId())
                    && metadata.ref().payloadHash().equals(entry.path("checksum").asText())
                    && metadata.ref().type().equals(entry.path("type").asText())
                    && metadata.ref().schemaVersion().equals(entry.path("schemaVersion").asText())
                    && metadata.ref().expiresAt().equals(Instant.parse(entry.path("retainedUntil").asText())));
            pending.add(root);
        }
        Set<String> collections=new LinkedHashSet<>();
        Map<Integer,RunToken> sources=new HashMap<>(); sources.put(definition.revision(),token);
        Map<Integer,FrozenCampaignRun> frozenSources=new HashMap<>();
        Map<String,String> groupVersions=new LinkedHashMap<>();
        while (!pending.isEmpty()) {
            ArtifactMetadata metadata=pending.removeFirst();
            ArtifactMetadata existing=closure.putIfAbsent(metadata.ref().artifactId(),metadata);
            if (existing!=null) {require(existing.equals(metadata));continue;}
            require(sameLine(definition,metadata) && clock.instant().isBefore(metadata.ref().expiresAt()));
            RunToken source=sources.computeIfAbsent(metadata.revision(),revision->token(caller,new CampaignReportDeliveryService.Reference(
                    definition.sessionId(),definition.runId(),definition.planId(),revision)));
            if (roots.contains(metadata.ref().artifactId()) && metadata.revision()!=definition.revision()) adoption(token,source,metadata);
            require(metadata.equals(runs.inspectArtifact(caller,metadata.ref().artifactId(),(user,actual)->caller.equals(user)&&metadata.equals(actual))));
            var child=runs.child(source,metadata.childId()).orElseThrow(CampaignSealedReportAccess::denied);
            require(child.state()==ChildState.READY && child.reason()==null && !child.callbackActive()
                    && metadata.actionId().equals(child.spec().actionId()));
            if (child.spec().mode()==ChildMode.ASYNC && child.spec().wire()!=null) {
                JsonNode request=CampaignArtifactReportRows.tree(child.spec().wire().bodyJson());
                if (request.path("scope").isObject()) {
                    String gid=request.path("gid").asText(),version=request.path("scope").path("enumerationVersion").asText();
                    require(!gid.isBlank() && version.matches("[a-f0-9]{64}")
                            && metadata.ref().scopeRef().equals(request.path("scope").path("parentScopeRef").asText()));
                    String previous=groupVersions.putIfAbsent(gid,version);require(previous==null||previous.equals(version));
                }
            }
            var action=runs.inspectAction(source,metadata.actionId()).orElseThrow(CampaignSealedReportAccess::denied);
            require(action.executorVersion().equals(metadata.executorVersion()));
            var step=frozenSources.computeIfAbsent(metadata.revision(),revision->FrozenCampaignRun.read(source.definition())).plan().steps().stream()
                    .filter(candidate->candidate.stepId().equals(action.stepId())).findFirst().orElseThrow(CampaignSealedReportAccess::denied);
            if (step.executionMode()==com.jupiter.shortlink.agent.campaignanalysisagent.planning.PlanSpec.ExecutionMode.FIXED)
                require(step.executor()!=null && step.executor().kind().name().equals(action.executorKind())
                        && step.executor().name().equals(action.executorName()) && step.executor().version().equals(action.executorVersion())
                        && frozenJson(step).equals(action.definitionJson()));
            if (child.spec().mode()==ChildMode.LOCAL) {
                var invocation=child.spec().localInvocation();
                require(invocation!=null && runs.isLocalOutputBound(source,metadata.childId(),metadata.ref()));
                require(invocation.inputs().values().stream().allMatch(input->sameRun(source.definition(),input)));
                pending.addAll(invocation.inputs().values());
                // Readers verify paired outputs and chained page proofs, so the exact producer's siblings are included.
                for (var output:invocation.outputs().values()) {
                    ArtifactMetadata sibling=runs.inspectArtifact(caller,output.artifactId(),(user,actual)->caller.equals(user)
                            && sameRun(source.definition(),actual) && metadata.childId().equals(actual.childId())
                            && metadata.actionId().equals(actual.actionId()) && output.type().equals(actual.ref().type())
                            && output.schemaVersion().equals(actual.ref().schemaVersion())
                            && output.scopeRef().equals(actual.ref().scopeRef()) && output.periodsRef().equals(actual.ref().periodsRef()));
                    require(runs.isLocalOutputBound(source,metadata.childId(),sibling.ref()));
                    pending.add(sibling);
                }
            } else if ("ScopeArtifact".equals(metadata.ref().type())) {
                var scope=FrozenScopeCollection.resolve(source.definition()).get(action.stepId()); require(scope!=null);
                collection(source,scope,metadata.ref().artifactId()); collections.add(source.definition().revision()+":"+action.stepId());
            } else require(metadata.ref().artifactId().equals(child.artifactId()));
        }
        Grant grant=new Grant(token,Map.copyOf(closure),Set.copyOf(collections),Map.copyOf(sources),Map.copyOf(groupVersions));
        grant.revalidate();
        return grant;
    }

    public final class Grant implements ArtifactAuthorizer {
        private final RunToken token;
        private final Map<String,ArtifactMetadata> artifacts;
        private final Set<String> collections;
        private final Map<Integer,RunToken> sources;
        private final Map<String,String> groupVersions;
        private Grant(RunToken token,Map<String,ArtifactMetadata> artifacts,Set<String> collections,Map<Integer,RunToken> sources,
                Map<String,String> groupVersions) {
            this.token=token;this.artifacts=artifacts;this.collections=collections;this.sources=sources;this.groupVersions=groupVersions;
        }
        public RunToken token() {return token;}
        public RunToken tokenFor(ArtifactMetadata metadata) {
            require(metadata.equals(artifacts.get(metadata.ref().artifactId()))); return sources.get(metadata.revision());
        }
        @Override public boolean mayRead(Caller caller,ArtifactMetadata metadata) {
            return caller.equals(token.definition().caller()) && metadata!=null
                    && metadata.equals(artifacts.get(metadata.ref().artifactId())) && clock.instant().isBefore(metadata.ref().expiresAt());
        }
        public void revalidate() {
            require(token.equals(CampaignSealedReportAccess.this.token(token.definition().caller(),CampaignReportDeliveryService.Reference.of(token.definition()))));
            for (RunToken source:sources.values()) require(source.equals(CampaignSealedReportAccess.this.token(source.definition().caller(),
                    CampaignReportDeliveryService.Reference.of(source.definition()))));
            for (String key:collections) {
                int delimiter=key.indexOf(':');RunToken source=sources.get(Integer.parseInt(key.substring(0,delimiter)));
                var bound=FrozenScopeCollection.resolve(source.definition()).get(key.substring(delimiter+1));
                collection(source,bound,"scope-artifact-"+CampaignRunStore.sha256(bound.definition().collectionId()));
            }
            for (var metadata:artifacts.values()) require(clock.instant().isBefore(metadata.ref().expiresAt()));
            groupVersions.forEach((gid,version)->currentGroup(token.definition().caller(),gid,version));
        }
    }

    private void current(RunToken token) {
        Caller caller=token.definition().caller();
        var principal=new AgentPrincipal(caller.tenantId(),caller.subject(),caller.authVersion(),false);
        require(principal.equals(authority.verifyCurrentPrincipal(principal)) && access.mayRead(caller,token.definition()));
    }
    private void collection(RunToken token,FrozenScopeCollection.Bound bound,String artifactId) {
        var definition=bound.definition();var action=definition.action();
        String hash=CampaignRunStore.sha256(frozenJson(List.of(definition.collectionId(),action.actionId(),action.stepId(),
                action.executorKind(),action.executorName(),action.executorVersion(),action.definitionHash(),definition.gid(),definition.expiresAt().toEpochMilli())));
        List<String> versions=jdbc.query("SELECT enumeration_version FROM campaign_scope_collection WHERE collection_id=? AND run_id=? AND revision=?"
                        +" AND action_id=? AND gid=? AND definition_hash=? AND expires_at=? AND collection_state='PUBLISHED' AND page_count>0"
                        +" AND artifact_id=? AND failure_code IS NULL",
                (rs,row)->rs.getString(1),definition.collectionId(),token.definition().runId(),token.definition().revision(),action.actionId(),
                definition.gid(),hash,definition.expiresAt().toEpochMilli(),artifactId);
        require(versions.size()==1 && versions.get(0)!=null && versions.get(0).matches("[a-f0-9]{64}"));
        currentGroup(token.definition().caller(),definition.gid(),versions.get(0));
    }
    private void currentGroup(Caller caller,String gid,String version) {
        var principal=new AgentPrincipal(caller.tenantId(),caller.subject(),caller.authVersion(),false);
        var page=authority.resolveGroupMembersPage(principal,gid,null,null);require(page!=null);
        page.requireMatches(new GroupMembersPage.Request(gid,null,null),caller.tenantId(),caller.subject(),caller.authVersion());
        require(version.equals(page.ownershipVersion()));
    }
    private static boolean sameRun(RunDefinition definition,ArtifactMetadata metadata) {
        return metadata!=null && definition.caller().equals(metadata.owner()) && definition.runId().equals(metadata.runId())
                && definition.planId().equals(metadata.planId()) && definition.revision()==metadata.revision();
    }
    private static boolean sameLine(RunDefinition definition,ArtifactMetadata metadata) {
        return metadata!=null && definition.caller().equals(metadata.owner()) && definition.runId().equals(metadata.runId())
                && definition.planId().equals(metadata.planId()) && metadata.revision()>0 && metadata.revision()<=definition.revision();
    }

    private void adoption(RunToken consumer,RunToken source,ArtifactMetadata metadata) {
        require("StatisticsJobPages".equals(metadata.ref().type()) && "statistics-job-pages/v1".equals(metadata.ref().schemaVersion()));
        var child=runs.child(source,metadata.childId()).orElseThrow(CampaignSealedReportAccess::denied);
        require(child.spec().wire()!=null && child.jobId()!=null && FrozenStatisticsJobQuery.FROZEN_SUBMIT_PATH.equals(child.spec().wire().path()));
        var matches=jdbc.query("SELECT c.step_id,c.expectation_json,c.expectation_hash,b.request_hash,b.scope_ref,b.periods_ref,b.artifact_id,b.output_contract_ref "
                        +"FROM campaign_statistics_consumer c JOIN campaign_statistics_job_binding b ON b.binding_id=c.binding_id "
                        +"WHERE c.run_id=? AND c.revision=? AND b.tenant_id=? AND b.subject_name=? AND b.auth_version=?"
                        +" AND b.producer_run_id=? AND b.producer_revision=? AND b.producer_child_id=? AND b.producer_definition_hash=?"
                        +" AND b.action_id=? AND b.executor_kind='TOOL' AND b.executor_name='statistics_query_job' AND b.executor_version=?"
                        +" AND b.job_id=? AND b.request_id=? AND b.request_hash=? AND b.artifact_id=? AND b.scope_ref=? AND b.periods_ref=? AND b.expires_at=? AND b.expires_at>?",
                (rs,row)-> {
                    String json=rs.getString(2);require(CampaignRunStore.sha256(json).equals(rs.getString(3)));
                    var expected=CampaignArtifactReportRows.tree(json);String stepId=rs.getString(1);
                    require(stepId.equals(expected.path("stepId").asText()) && rs.getString(4).equals(expected.path("requestHash").asText())
                            && "TOOL".equals(expected.path("executor").path("kind").asText())
                            && "statistics_query_job".equals(expected.path("executor").path("name").asText())
                            && metadata.executorVersion().equals(expected.path("executor").path("version").asText())
                            && rs.getString(8).equals(expected.path("outputContractRef").asText())
                            && rs.getString(5).equals(expected.path("target").path("scopeRef").asText())
                            && rs.getString(6).equals(expected.path("target").path("periodsRef").asText())
                            && rs.getString(7).equals(expected.path("target").path("artifactId").asText()));
                    var query=FrozenStatisticsJobQuery.resolve(consumer.definition(),StatisticsJobFixedExecutor.REF).get(stepId);require(query!=null);
                    var originalQuery=FrozenStatisticsJobQuery.resolve(source.definition(),StatisticsJobFixedExecutor.REF).get(stepId);
                    require(originalQuery!=null && originalQuery.child().equals(child.spec())
                            && originalQuery.target().artifactId().equals(metadata.ref().artifactId())
                            && originalQuery.scopeRef().equals(metadata.ref().scopeRef()) && originalQuery.periodsRef().equals(metadata.ref().periodsRef())
                            && FrozenCampaignRun.read(consumer.definition()).plan().steps().stream().anyMatch(step->step.stepId().equals(stepId)
                            && step.outputContractRef().equals(expected.path("outputContractRef").asText())));
                    var original=CampaignArtifactReportRows.tree(child.spec().wire().bodyJson()).deepCopy();
                    ((com.fasterxml.jackson.databind.node.ObjectNode)original).remove("requestId");
                    Map<String,Object> current=new TreeMap<>(query.request());current.remove("requestId");
                    require(original.equals(CampaignArtifactReportRows.tree(frozenJson(current)))
                            && query.scopeRef().equals(metadata.ref().scopeRef()) && query.periodsRef().equals(metadata.ref().periodsRef()));
                    return stepId;
                },consumer.definition().runId(),consumer.definition().revision(),metadata.owner().tenantId(),metadata.owner().subject(),metadata.owner().authVersion(),
                metadata.runId(),metadata.revision(),metadata.childId(),source.definition().definitionHash(),metadata.actionId(),metadata.executorVersion(),
                child.jobId(),child.spec().requestId(),child.spec().wire().hash(),metadata.ref().artifactId(),metadata.ref().scopeRef(),metadata.ref().periodsRef(),
                metadata.ref().expiresAt().toEpochMilli(),clock.millis());
        require(!matches.isEmpty());
    }
    private static void require(boolean valid) {if(!valid)throw denied();}
    static String frozenJson(Object value) {
        try {return FROZEN_JSON.writeValueAsString(value);}
        catch (JsonProcessingException invalid) {throw new IllegalArgumentException("REPORT_FROZEN_JSON_INVALID",invalid);}
    }
    private static SecurityException denied() {return new SecurityException("REPORT_HISTORICAL_ACCESS_DENIED");}
}
