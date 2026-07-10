package com.nageoffer.shortlink.agent.e2e;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

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

    private Path locateScript() {
        Path workingDirectory = Path.of("").toAbsolutePath();
        Path fromRepositoryRoot = workingDirectory.resolve("scripts/risk-profile-policy-e2e.ps1");
        if (Files.exists(fromRepositoryRoot)) {
            return fromRepositoryRoot;
        }
        Path fromModule = workingDirectory.resolve("../scripts/risk-profile-policy-e2e.ps1").normalize();
        assertThat(fromModule).exists();
        return fromModule;
    }
}
