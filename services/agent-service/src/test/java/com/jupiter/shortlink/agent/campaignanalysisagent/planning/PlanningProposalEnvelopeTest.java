package com.jupiter.shortlink.agent.campaignanalysisagent.planning;

import static org.junit.jupiter.api.Assertions.*;

import java.util.List;
import org.junit.jupiter.api.Test;

class PlanningProposalEnvelopeTest {
    private static final String PROPOSAL = "{\"schemaVersion\":\"campaign-planning-proposal/v1\",\"steps\":[],\"coverageBindings\":[],\"gaps\":[]}";

    @Test
    void acceptsOnlyOneCompleteJsonPresentationWrapperWithoutChangingProposal() {
        var expected = PlanningProposal.parse(PROPOSAL);
        for (String wrapped : List.of("```json\n" + PROPOSAL + "\n```",
                " \n```json\r\n" + PROPOSAL + "\r\n```\r\n ", "```\n" + PROPOSAL + "\n```",
                "```JSON\n" + PROPOSAL + "\n```")) {
            assertEquals(PROPOSAL, StrictStructuredJson.unwrapSingleFence(wrapped));
            assertEquals(expected, PlanningProposal.parse(wrapped));
        }
        var raw = " \n" + PROPOSAL + "\n";
        assertEquals(raw, StrictStructuredJson.unwrapSingleFence(raw));
        var small = new PlanningProposal.Limits(1024, PROPOSAL.length(), 16);
        assertDoesNotThrow(() -> PlanningProposal.parse(PROPOSAL, small));
        assertEquals("PLANNING_JSON_TOO_LARGE", assertThrows(IllegalArgumentException.class,
                () -> PlanningProposal.parse("```json\n" + PROPOSAL + "\n```", small)).getMessage());
    }

    @Test
    void rejectsProseMultipleBlocksDuplicateKeysAndSchemaViolationsInsideTheWrapper() {
        String fenced = "```json\n" + PROPOSAL + "\n```";
        String duplicate = PROPOSAL.replace("\"steps\":[]", "\"steps\":[],\"steps\":[]");
        String unknown = PROPOSAL.substring(0, PROPOSAL.length() - 1) + ",\"extra\":true}";
        for (String invalid : List.of("Result:\n" + fenced, fenced + "\nDone.", fenced + "\n" + fenced,
                "```json\n" + PROPOSAL, "```javascript\n" + PROPOSAL + "\n```",
                "```json\n" + PROPOSAL + "\n" + PROPOSAL + "\n```",
                "```json\n" + duplicate + "\n```", "```json\n" + unknown + "\n```",
                "```json\n[]\n```")) {
            assertThrows(IllegalArgumentException.class, () -> PlanningProposal.parse(invalid));
        }
        // The server's saved request format remains strict JSON, not a presentation format.
        assertThrows(IllegalArgumentException.class, () -> PlanningProposal.decodeRequest("```json\n{}\n```"));
    }
}
