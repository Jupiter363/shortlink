package com.jupiter.shortlink.analytics.api.support;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIOException;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.nio.file.Files;
import java.nio.file.Path;

class RepositoryFilesTest {
    @TempDir Path temporary;

    private static final String POM = """
            <project xmlns="http://maven.apache.org/POM/4.0.0">
              <modelVersion>4.0.0</modelVersion>
              <groupId>com.jupiter.shortlink</groupId><artifactId>shortlink-all</artifactId>
              <version>1.0-SNAPSHOT</version><packaging>pom</packaging>
              <modules><module>shortlink-analytics-api</module></modules>
            </project>
            """;

    @ParameterizedTest
    @ValueSource(strings = {"", "shortlink-analytics-api", "services/shortlink-analytics-api", "services/shortlink-analytics-api/src/test/java"})
    void resolvesSameResourceAtDifferentDepthsWithoutGitMetadata(String relativeCwd) throws Exception {
        Path root = repository(temporary.resolve("export"), POM);
        Path target = target(root);
        Path cwd = Files.createDirectories(root.resolve(relativeCwd));
        assertThat(root.resolve(".git")).doesNotExist();
        assertThat(RepositoryFiles.analyticsControlSchema(cwd)).isEqualTo(target.toRealPath());
    }

    @ParameterizedTest
    @ValueSource(strings = {"groupId", "artifactId", "packaging"})
    void rejectsWrongRootIdentityEvenWhenTargetExists(String field) throws Exception {
        String wrong = POM.replaceFirst("<" + field + ">[^<]+</" + field + ">", "<" + field + ">wrong</" + field + ">");
        Path root = repository(temporary.resolve("wrong"), wrong);
        target(root);
        assertThatIOException().isThrownBy(() -> RepositoryFiles.analyticsControlSchema(root))
                .withMessageContaining("Cannot locate");
    }

    @Test
    void parentCoordinatesDoNotMakeALeafPomTheRepositoryRoot() throws Exception {
        String leaf = """
                <project xmlns="http://maven.apache.org/POM/4.0.0"><modelVersion>4.0.0</modelVersion>
                  <parent><groupId>com.jupiter.shortlink</groupId><artifactId>shortlink-all</artifactId>
                    <version>1.0-SNAPSHOT</version></parent>
                  <artifactId>shortlink-analytics-api</artifactId><packaging>pom</packaging>
                  <modules><module>nested</module></modules>
                </project>
                """;
        Path root = repository(temporary.resolve("export"), POM);
        Path expected = target(root);
        Path leafDirectory = repository(root.resolve("services/shortlink-analytics-api"), leaf);
        target(leafDirectory);
        assertThat(RepositoryFiles.analyticsControlSchema(leafDirectory)).isEqualTo(expected.toRealPath());
    }

    @Test
    void missingTargetInAnExportDoesNotFallBackToTheOuterCheckout() throws Exception {
        Path outer = repository(temporary.resolve("outer"), POM);
        target(outer);
        Path inner = repository(outer.resolve(".work/candidate"), POM);
        assertThatIOException().isThrownBy(() -> RepositoryFiles.analyticsControlSchema(inner))
                .withMessageContaining("Repository target is missing");
    }

    @Test
    void rejectsDirectoryInPlaceOfTargetFile() throws Exception {
        Path root = repository(temporary.resolve("export"), POM);
        Files.createDirectories(root.resolve("deploy/mysql/002-analytics-control-schema.sql"));
        assertThatIOException().isThrownBy(() -> RepositoryFiles.analyticsControlSchema(root))
                .withMessageContaining("Repository target is missing");
    }

    @Test
    void rejectsDoctypeInsteadOfResolvingExternalEntities() throws Exception {
        String unsafe = "<?xml version=\"1.0\"?><!DOCTYPE project SYSTEM \"file:///missing-external-pom.dtd\">" + POM;
        Path root = repository(temporary.resolve("export"), unsafe);
        target(root);
        assertThatIOException().isThrownBy(() -> RepositoryFiles.analyticsControlSchema(root))
                .withMessageContaining("Cannot locate");
    }

    @Test
    @EnabledOnOs(OS.LINUX)
    void rejectsTargetSymlinkOutsideTheRepository() throws Exception {
        Path root = repository(temporary.resolve("export"), POM);
        Path outside = Files.writeString(temporary.resolve("outside"), "not a repository fixture");
        Path link = root.resolve("deploy/mysql/002-analytics-control-schema.sql");
        Files.createDirectories(link.getParent());
        Files.createSymbolicLink(link, outside);
        assertThatIOException().isThrownBy(() -> RepositoryFiles.analyticsControlSchema(root))
                .withMessageContaining("escapes its root");
    }

    @Test
    void resolvesTheActualCheckedOutResource() throws Exception {
        assertThat(RepositoryFiles.analyticsControlSchema(Path.of(""))).isRegularFile();
    }

    private Path repository(Path root, String pom) throws Exception {
        Files.createDirectories(root);
        Files.writeString(root.resolve("pom.xml"), pom);
        return root;
    }

    private Path target(Path root) throws Exception {
        Path target = root.resolve("deploy/mysql/002-analytics-control-schema.sql");
        Files.createDirectories(target.getParent());
        return Files.writeString(target, "fixture-content");
    }
}
