package com.jupiter.shortlink.agent.e2e;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

@org.junit.jupiter.api.Tag("e2e")
class RiskProfileE2eScriptContractTest {

    @Test
    void scriptAcceptsCurrentAndLegacyRunOnceCountFields() throws IOException {
        Path script = locateScript();
        String content = Files.readString(script);

        assertThat(content)
                .contains("Properties.Name -contains \"scannedCount\"")
                .contains("Properties.Name -contains \"scannedShortLinks\"")
                .contains("$batch.data.scannedCount")
                .contains("$batch.data.scannedShortLinks")
                .contains("run-once scannedCount is 0");
    }

    private Path locateScript() throws IOException {
        return com.jupiter.shortlink.agent.support.RepositoryFiles.riskProfileScript(Path.of(""));
    }
}
