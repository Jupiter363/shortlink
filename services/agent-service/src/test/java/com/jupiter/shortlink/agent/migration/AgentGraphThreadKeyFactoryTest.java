package com.jupiter.shortlink.agent.migration;

import com.jupiter.shortlink.agent.harness.checkpoint.AgentGraphThreadKeyFactory;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class AgentGraphThreadKeyFactoryTest {

    @Test
    void graphVersionIsPartOfThePersistentThreadNamespace() {
        String versionOne = AgentGraphThreadKeyFactory.create("campaign-analysis", "v1", "session-001");
        String versionTwo = AgentGraphThreadKeyFactory.create("campaign-analysis", "v2", "session-001");

        assertThat(versionOne).isNotEqualTo(versionTwo);
        assertThat(versionOne).isNotBlank();
        assertThat(versionTwo).isNotBlank();
    }

    @Test
    void differentGraphsCannotResumeEachOthersSessionCheckpoint() {
        assertThat(AgentGraphThreadKeyFactory.create("campaign-analysis", "v1", "session-001"))
                .isNotEqualTo(AgentGraphThreadKeyFactory.create("security-risk", "v1", "session-001"));
    }

    @Test
    void rejectsBlankPersistentNamespaceSegments() {
        assertThatThrownBy(() -> AgentGraphThreadKeyFactory.create(" ", "v1", "session-001"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("graphName must not be blank");
        assertThatThrownBy(() -> AgentGraphThreadKeyFactory.create("campaign-analysis", " ", "session-001"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("graphVersion must not be blank");
        assertThatThrownBy(() -> AgentGraphThreadKeyFactory.create("campaign-analysis", "v1", " "))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("sessionId must not be blank");
    }
}
