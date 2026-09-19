package com.jupiter.shortlink.agent.campaignanalysisagent.runtime.persistence;

import com.jupiter.shortlink.agent.campaignanalysisagent.runtime.persistence.CampaignRunStore.ActionSpec;
import com.jupiter.shortlink.agent.campaignanalysisagent.runtime.persistence.CampaignRunStore.Artifact;
import com.jupiter.shortlink.agent.campaignanalysisagent.runtime.persistence.CampaignRunStore.ArtifactDraft;
import com.jupiter.shortlink.agent.campaignanalysisagent.runtime.persistence.CampaignRunStore.ArtifactRef;
import com.jupiter.shortlink.agent.campaignanalysisagent.runtime.persistence.CampaignRunStore.Caller;
import com.jupiter.shortlink.agent.campaignanalysisagent.runtime.persistence.CampaignRunStore.ChildMode;
import com.jupiter.shortlink.agent.campaignanalysisagent.runtime.persistence.CampaignRunStore.ChildSpec;
import com.jupiter.shortlink.agent.campaignanalysisagent.runtime.persistence.CampaignRunStore.ChildState;
import com.jupiter.shortlink.agent.campaignanalysisagent.runtime.persistence.CampaignRunStore.DispatchPermit;
import com.jupiter.shortlink.agent.campaignanalysisagent.runtime.persistence.CampaignRunStore.Limits;
import com.jupiter.shortlink.agent.campaignanalysisagent.runtime.persistence.CampaignRunStore.RunDefinition;
import com.jupiter.shortlink.agent.campaignanalysisagent.runtime.persistence.CampaignRunStore.RunToken;
import com.jupiter.shortlink.agent.campaignanalysisagent.runtime.persistence.CampaignRunStore.WireRequest;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;
import org.springframework.transaction.support.TransactionTemplate;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.HexFormat;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class JdbcCampaignArtifactTest {
    private static final Instant NOW = Instant.parse("2026-09-19T14:00:00Z");
    private static final Clock CLOCK = Clock.fixed(NOW, ZoneOffset.UTC);
    private static final Caller OWNER = new Caller("tenant-a", "analyst-a", 7);
    private static final String ACTION_ID = "action-compare";
    private static final String EXECUTOR_VERSION = "compare-v3";

    @Test
    void publishedArtifactSurvivesStoreReopenWithImmutablePayloadAndCompleteProvenance() throws Exception {
        Fixture fixture = fixture(Limits.defaults());
        CampaignRunStore store = fixture.store(CLOCK);
        RunToken run = prepareRun(store);
        DispatchPermit permit = prepareDispatch(store, run, "child-current");
        ArtifactDraft base = draft("artifact-current", "{\"rows\":[{\"province\":\"浙江\",\"pv\":37}]}");
        ArtifactDraft draft = new ArtifactDraft(base.artifactId(), base.type(), base.schemaVersion(), base.scopeRef(),
                base.periodsRef(), base.qualityJson(), base.provenanceJson(),
                base.expiresAt().plusNanos(123456789), base.payloadJson());
        ArtifactRef published;
        try {
            published = store.publishReady(permit, draft);
            assertEquals(published, store.publishReady(permit, draft), "The identical draft must publish idempotently");
        } finally {
            store.callbackExited(permit);
        }

        CampaignRunStore reopened = fixture.store(CLOCK);
        AtomicInteger authorizationChecks = new AtomicInteger();
        Artifact restored = reopened.readArtifact(OWNER, published.artifactId(), (current, metadata) -> {
            authorizationChecks.incrementAndGet();
            assertEquals(OWNER, current);
            assertEquals(OWNER, metadata.owner());
            assertEquals("scope-frozen-1", metadata.ref().scopeRef());
            assertEquals("periods-current-vs-baseline", metadata.ref().periodsRef());
            return true;
        });

        String independentHash = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                .digest(draft.payloadJson().getBytes(StandardCharsets.UTF_8)));
        assertAll(
                () -> assertEquals(1, authorizationChecks.get()),
                () -> assertEquals(draft.payloadJson(), restored.payloadJson()),
                () -> assertEquals(published, restored.metadata().ref()),
                () -> assertEquals(independentHash, restored.metadata().ref().payloadHash()),
                () -> assertEquals("CAMPAIGN_COMPARISON", restored.metadata().ref().type()),
                () -> assertEquals("comparison/3", restored.metadata().ref().schemaVersion()),
                () -> assertEquals(Instant.ofEpochMilli(draft.expiresAt().toEpochMilli()), restored.metadata().ref().expiresAt()),
                () -> assertEquals(draft.qualityJson(), restored.metadata().qualityJson()),
                () -> assertEquals(draft.provenanceJson(), restored.metadata().provenanceJson()),
                () -> assertEquals("run-1", restored.metadata().runId()),
                () -> assertEquals("plan-1", restored.metadata().planId()),
                () -> assertEquals(1, restored.metadata().revision()),
                () -> assertEquals(ACTION_ID, restored.metadata().actionId()),
                () -> assertEquals("child-current", restored.metadata().childId()),
                () -> assertEquals(EXECUTOR_VERSION, restored.metadata().executorVersion()),
                () -> assertEquals(ChildState.READY, reopened.child(run, "child-current").orElseThrow().state()),
                () -> assertFalse(reopened.child(run, "child-current").orElseThrow().callbackActive())
        );
        assertEquals(restored, reopened.readArtifact(OWNER, published.artifactId(), (current, metadata) -> true));
    }

    @Test
    void readEnforcesOwnerAndCurrentAuthorizationAndExpiryBeforeReturningPayload() {
        Fixture fixture = fixture(Limits.defaults());
        CampaignRunStore store = fixture.store(CLOCK);
        RunToken run = prepareRun(store);
        DispatchPermit permit = prepareDispatch(store, run, "child-auth");
        ArtifactDraft draft = draft("artifact-auth", "{\"pv\":73}");
        try {
            store.publishReady(permit, draft);
        } finally {
            store.callbackExited(permit);
        }
        CampaignRunStore reopened = fixture.store(CLOCK);
        AtomicInteger authorizationChecks = new AtomicInteger();
        for (Caller wrongCaller : List.of(
                new Caller("tenant-b", OWNER.subject(), OWNER.authVersion()),
                new Caller(OWNER.tenantId(), "analyst-b", OWNER.authVersion()),
                new Caller(OWNER.tenantId(), OWNER.subject(), OWNER.authVersion() + 1))) {
            assertThrows(SecurityException.class, () -> reopened.readArtifact(wrongCaller, draft.artifactId(),
                    (current, metadata) -> {
                        authorizationChecks.incrementAndGet();
                        return true;
                    }));
        }
        assertEquals(0, authorizationChecks.get(), "Cross-owner reads must not reach external authorization");

        assertThrows(SecurityException.class, () -> reopened.readArtifact(OWNER, draft.artifactId(),
                (current, metadata) -> {
                    authorizationChecks.incrementAndGet();
                    return false;
                }));
        assertEquals(1, authorizationChecks.get());
        CampaignRunStore justBeforeExpiry = fixture.store(Clock.fixed(draft.expiresAt().minusSeconds(1), ZoneOffset.UTC));
        assertEquals(draft.payloadJson(), justBeforeExpiry.readArtifact(OWNER, draft.artifactId(),
                (current, metadata) -> true).payloadJson());

        CampaignRunStore atExpiry = fixture.store(Clock.fixed(draft.expiresAt(), ZoneOffset.UTC));
        assertThrows(SecurityException.class, () -> atExpiry.readArtifact(OWNER, draft.artifactId(),
                (current, metadata) -> {
                    authorizationChecks.incrementAndGet();
                    return true;
                }));
        assertEquals(1, authorizationChecks.get(), "Expired evidence must be rejected before authorization");
    }

    @Test
    void conflictingArtifactIdentityCannotPublishSecondChildOrOverwriteExistingEvidence() {
        Fixture fixture = fixture(Limits.defaults());
        CampaignRunStore store = fixture.store(CLOCK);
        RunToken run = prepareRun(store);
        DispatchPermit first = prepareDispatch(store, run, "child-first");
        ArtifactDraft original = draft("artifact-shared-id", "{\"pv\":37}");
        try {
            store.publishReady(first, original);
        } finally {
            store.callbackExited(first);
        }
        Artifact saved = store.readArtifact(OWNER, original.artifactId(), (current, metadata) -> true);

        DispatchPermit second = prepareDispatch(store, run, "child-second");
        ArtifactDraft changedMetadata = new ArtifactDraft(original.artifactId(), original.type(), "comparison/4",
                "scope-other", original.periodsRef(), original.qualityJson(), original.provenanceJson(),
                original.expiresAt(), original.payloadJson());
        ArtifactDraft changedPayload = draft(original.artifactId(), "{\"pv\":999}");
        try {
            for (ArtifactDraft conflict : List.of(changedMetadata, changedPayload)) {
                assertThrows(IllegalStateException.class, () -> store.publishReady(second, conflict));
                CampaignRunStore reopened = fixture.store(CLOCK);
                assertEquals(saved, reopened.readArtifact(OWNER, original.artifactId(), (current, metadata) -> true));
                assertEquals(ChildState.READY, reopened.child(run, "child-first").orElseThrow().state());
                assertEquals(ChildState.DISPATCHING, reopened.child(run, "child-second").orElseThrow().state());
                assertNull(reopened.child(run, "child-second").orElseThrow().artifactId());
                assertTrue(reopened.child(run, "child-second").orElseThrow().callbackActive());
                assertEquals(1, fixture.jdbc().queryForObject("SELECT COUNT(*) FROM campaign_artifact", Integer.class));
                assertEquals(1, fixture.jdbc().queryForObject("SELECT COUNT(*) FROM campaign_artifact_payload", Integer.class));
                assertEquals(0, fixture.jdbc().queryForObject("""
                        SELECT COUNT(*) FROM campaign_artifact_payload p
                        LEFT JOIN campaign_artifact a ON a.artifact_id = p.artifact_id
                        WHERE a.artifact_id IS NULL
                        """, Integer.class));
            }
        } finally {
            store.callbackExited(second);
        }
        assertFalse(store.child(run, "child-second").orElseThrow().callbackActive());
    }

    @Test
    void explicitArtifactByteLimitRejectsUtf8PayloadBeforeJsonParsingAndWithoutPublishing() {
        Fixture fixture = fixture(new Limits(1024, 1024, 64));
        CampaignRunStore store = fixture.store(CLOCK);
        RunToken run = prepareRun(store);
        DispatchPermit permit = prepareDispatch(store, run, "child-oversized");
        String oversizedJson = "{\"text\":\"" + "省".repeat(30) + "\"}";
        String malformedOversizedJson = oversizedJson.substring(0, oversizedJson.length() - 1) + "]";
        assertTrue(oversizedJson.length() < 64, "A character limit would incorrectly accept this payload");
        assertTrue(oversizedJson.getBytes(StandardCharsets.UTF_8).length > 64);
        try {
            IllegalArgumentException validJsonError = assertThrows(IllegalArgumentException.class,
                    () -> store.publishReady(permit, minimalDraft("artifact-oversized", oversizedJson)));
            IllegalArgumentException invalidJsonError = assertThrows(IllegalArgumentException.class,
                    () -> store.publishReady(permit, minimalDraft("artifact-oversized", malformedOversizedJson)));
            assertNotNull(validJsonError.getMessage());
            assertEquals(validJsonError.getMessage(), invalidJsonError.getMessage(),
                    "Both payloads must fail at the byte boundary before the malformed JSON is parsed");
            assertEquals(ChildState.DISPATCHING, store.child(run, "child-oversized").orElseThrow().state());
            assertNull(store.child(run, "child-oversized").orElseThrow().artifactId());
            assertEquals(0, fixture.jdbc().queryForObject("SELECT COUNT(*) FROM campaign_artifact", Integer.class));
            assertEquals(0, fixture.jdbc().queryForObject("SELECT COUNT(*) FROM campaign_artifact_payload", Integer.class));
        } finally {
            store.callbackExited(permit);
        }
        assertFalse(store.child(run, "child-oversized").orElseThrow().callbackActive());
    }

    private static RunToken prepareRun(CampaignRunStore store) {
        RunToken run = store.createRun(new RunDefinition(OWNER, "session-1", "run-1", "plan-1", 1,
                "{\"scopeRef\":\"scope-frozen-1\",\"periodsRef\":\"periods-current-vs-baseline\"}"));
        store.prepareAction(run, new ActionSpec(ACTION_ID, "step-compare", "TOOL", "campaign_compare",
                EXECUTOR_VERSION, "{\"comparison\":\"current-vs-baseline\"}"));
        return run;
    }

    private static DispatchPermit prepareDispatch(CampaignRunStore store, RunToken run, String childId) {
        store.prepareChild(run, new ChildSpec(childId, ACTION_ID, ChildMode.SYNC, "request-" + childId,
                new WireRequest("GET", "/analytics/campaign/compare", "{\"scopeRef\":\"scope-frozen-1\"}")));
        return store.beginDispatch(run, childId);
    }

    private static ArtifactDraft draft(String artifactId, String payload) {
        return new ArtifactDraft(artifactId, "CAMPAIGN_COMPARISON", "comparison/3", "scope-frozen-1",
                "periods-current-vs-baseline", "{\"status\":\"PARTIAL\",\"missingWindows\":2}",
                "{\"snapshotId\":\"snapshot-19\",\"recoveryEpoch\":4,\"manifestSelectionHash\":\"selection-hash\"}",
                NOW.plusSeconds(3600), payload);
    }

    private static ArtifactDraft minimalDraft(String artifactId, String payload) {
        return new ArtifactDraft(artifactId, "CAMPAIGN_COMPARISON", "comparison/3", "scope-frozen-1",
                "periods-current-vs-baseline", "{}", "{}", NOW.plusSeconds(3600), payload);
    }

    private static Fixture fixture(Limits limits) {
        DriverManagerDataSource dataSource = new DriverManagerDataSource(
                "jdbc:h2:mem:campaign_artifact_" + UUID.randomUUID() + ";MODE=MySQL;DB_CLOSE_DELAY=-1", "sa", "");
        dataSource.setDriverClassName("org.h2.Driver");
        new ResourceDatabasePopulator(new ClassPathResource("sql/migration/V20260919__campaign_run_ledger.sql"))
                .execute(dataSource);
        return new Fixture(new JdbcTemplate(dataSource),
                new TransactionTemplate(new DataSourceTransactionManager(dataSource)), limits);
    }

    private record Fixture(JdbcTemplate jdbc, TransactionTemplate transactions, Limits limits) {
        private CampaignRunStore store(Clock clock) {
            return new JdbcCampaignRunStore(jdbc, transactions, clock, limits);
        }
    }
}
