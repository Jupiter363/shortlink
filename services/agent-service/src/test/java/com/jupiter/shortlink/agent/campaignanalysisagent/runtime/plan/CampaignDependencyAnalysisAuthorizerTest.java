package com.jupiter.shortlink.agent.campaignanalysisagent.runtime.plan;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.jupiter.shortlink.agent.business.shortlink.AgentAuthorityClient;
import com.jupiter.shortlink.agent.campaignanalysisagent.planning.CapabilityCatalog.TypeRef;
import com.jupiter.shortlink.agent.campaignanalysisagent.planning.FrozenInputSet;
import com.jupiter.shortlink.agent.campaignanalysisagent.runtime.persistence.CampaignRunStore.Caller;
import com.jupiter.shortlink.agent.harness.security.AgentPrincipal;
import com.jupiter.shortlink.contract.GroupMembersPage;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;

class CampaignDependencyAnalysisAuthorizerTest {
    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-09-23T00:00:00Z"), ZoneOffset.UTC);
    private static final AgentPrincipal PRINCIPAL = new AgentPrincipal("1001", "alice", 7, false);
    private static final Caller CALLER = new Caller("1001", "alice", 7);

    @Test
    void currentGroupAndEveryExactFrozenInputUseAreRechecked() throws Exception {
        var fixture = new Fixture();
        FrozenInputSet inputs = fixture.inputs();
        var gate = fixture.authorizer.inputAuthorizer(inputs);
        assertThat(fixture.authorizer.mayExecute(CALLER, inputs)).isTrue();
        TypeRef periodsType = inputs.inputContracts().get("periods").type();
        Object periods = inputs.inputValues().get("periods");
        assertThat(gate.mayUse(CALLER, periodsType, periods)).isTrue();
        assertThat(gate.mayUse(CALLER, periodsType, "period.v1:2026-01-01:2026-01-02")).isFalse();
        assertThat(gate.mayUse(CALLER, inputs.inputContracts().get("collectionDefinition").type(), periods))
                .isFalse();
        fixture.current.set(false);
        assertThat(fixture.authorizer.mayExecute(CALLER, inputs)).isFalse();
        assertThat(gate.mayUse(CALLER, periodsType, periods)).isFalse();
    }

    @Test
    void foreignGroupAndTamperedSchemaOrSkillPinFailBeforeBusinessIo() throws Exception {
        var fixture = new Fixture();
        FrozenInputSet inputs = fixture.inputs();
        fixture.foreignGroup.set(true);
        assertThat(fixture.authorizer.mayExecute(CALLER, inputs)).isFalse();
        fixture.foreignGroup.set(false);
        clearInvocations(fixture.authority);

        Map<String, Object> invalidOperation = new LinkedHashMap<>(inputs.inputValues());
        Map<String, Object> operation = object(invalidOperation.get("operation"));
        operation.put("schemaVersion", "forged-operation/v1");
        invalidOperation.put("operation", operation);
        FrozenInputSet forgedSchema = new FrozenInputSet(inputs.inputSetRef(), inputs.runId(),
                inputs.inputContracts(), invalidOperation);
        assertThat(fixture.authorizer.mayExecute(CALLER, forgedSchema)).isFalse();

        Map<String, Object> invalidPin = new LinkedHashMap<>(inputs.inputValues());
        Map<String, Object> selection = object(invalidPin.get("selectionDefinition"));
        Map<String, Object> pin = object(selection.get("skillPin"));
        pin.put("sha256", "0".repeat(64));
        selection.put("skillPin", pin);
        invalidPin.put("selectionDefinition", selection);
        FrozenInputSet forgedPin = new FrozenInputSet(inputs.inputSetRef(), inputs.runId(),
                inputs.inputContracts(), invalidPin);
        assertThat(fixture.authorizer.mayExecute(CALLER, forgedPin)).isFalse();
        verifyNoInteractions(fixture.authority);
    }

    private static Map<String, Object> object(Object value) {
        Map<String, Object> copy = new LinkedHashMap<>();
        ((Map<?, ?>) value).forEach((key, nested) -> copy.put(key.toString(), nested));
        return copy;
    }

    private static final class Fixture {
        final AgentAuthorityClient authority = mock(AgentAuthorityClient.class);
        final AtomicBoolean current = new AtomicBoolean(true);
        final AtomicBoolean foreignGroup = new AtomicBoolean();
        final CampaignDependencyAnalysisPlanFactory plans;
        final CampaignDependencyAnalysisAuthorizer authorizer;

        Fixture() throws Exception {
            Path root = new ClassPathResource("campaign-skills").getFile().toPath();
            plans = new CampaignDependencyAnalysisPlanFactory(root, CLOCK);
            authorizer = new CampaignDependencyAnalysisAuthorizer(authority, plans);
            when(authority.verifyCurrentPrincipal(any(AgentPrincipal.class))).thenAnswer(call -> {
                if (!current.get()) throw new SecurityException("revoked");
                return call.getArgument(0);
            });
            when(authority.resolveGroupMembersPage(any(AgentPrincipal.class), anyString(), isNull(), isNull()))
                    .thenAnswer(call -> {
                        AgentPrincipal principal = call.getArgument(0);
                        String gid = call.getArgument(1);
                        return new GroupMembersPage(GroupMembersPage.SCHEMA, principal.tenantId(),
                                principal.username(), principal.authVersion(),
                                foreignGroup.get() ? "other-group" : gid,
                                "a".repeat(64), null, List.of(1L, 2L), null);
                    });
        }

        FrozenInputSet inputs() {
            var request = new CampaignDependencyAnalysisPlanFactory.Request("group-a",
                    "2026-09-01", "2026-09-01", "2026-09-02", "2026-09-02",
                    "PV", List.of("province", "device"), List.of());
            return plans.prepare(CALLER, "dependency-session", "invocation-1", request,
                    CLOCK.instant().plusSeconds(3600)).frozen().inputs();
        }
    }
}
