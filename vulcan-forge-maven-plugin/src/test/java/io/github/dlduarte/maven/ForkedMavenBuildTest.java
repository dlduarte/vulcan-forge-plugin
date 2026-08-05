package io.github.dlduarte.maven;

import org.apache.maven.execution.DefaultMavenExecutionRequest;
import org.apache.maven.execution.DefaultMavenExecutionResult;
import org.apache.maven.execution.MavenExecutionRequest;
import org.apache.maven.execution.MavenSession;
import org.apache.maven.plugin.logging.SystemStreamLog;
import org.apache.maven.shared.invoker.InvocationRequest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Properties;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Montagem do build filho: o fork tem de acontecer na raiz do reator, nao no modulo corrente. */
class ForkedMavenBuildTest {

    @TempDir
    Path root;

    private File rootPom;
    private DefaultMavenExecutionRequest request;

    @BeforeEach
    void setUp() throws IOException {
        rootPom = root.resolve("pom.xml").toFile();
        Files.writeString(rootPom.toPath(), "<project/>");

        request = new DefaultMavenExecutionRequest();
        request.setPom(rootPom);
        request.setBaseDirectory(root.toFile());
        request.setUserProperties(new Properties());
    }

    @SuppressWarnings("deprecation")
    private ForkedMavenBuild forkedBuild() {
        MavenSession session = new MavenSession(null, null, request, new DefaultMavenExecutionResult());
        return new ForkedMavenBuild(session, new SystemStreamLog());
    }

    private InvocationRequest buildRequest(List<String> goals, Properties props, boolean alsoMakeUpstream) {
        return forkedBuild().buildRequest(goals, props, alsoMakeUpstream);
    }

    @Test
    void forksOnTheExecutionRootPomNotOnAModule() {
        InvocationRequest req = buildRequest(List.of("clean", "install"), null, true);

        assertEquals(rootPom, req.getPomFile());
        assertEquals(root.toFile(), req.getBaseDirectory());
        assertEquals(List.of("clean", "install"), req.getGoals());
        assertTrue(req.isBatchMode());
    }

    @Test
    void fallsBackToExecutionRootDirectoryWhenNoPomWasGiven() {
        request.setPom(null);

        InvocationRequest req = buildRequest(List.of("clean", "deploy"), null, false);

        assertEquals(new File(root.toFile(), "pom.xml"), req.getPomFile());
    }

    @Test
    void alwaysMarksTheChildAsForked() {
        InvocationRequest req = buildRequest(List.of("clean", "install"), null, true);

        assertEquals("true", req.getProperties().getProperty(ForkedMavenBuild.FORKED_FLAG));
    }

    @Test
    void forkedChildSessionIsDetected() {
        request.getUserProperties().setProperty(ForkedMavenBuild.FORKED_FLAG, "true");

        @SuppressWarnings("deprecation")
        MavenSession session = new MavenSession(null, null, request, new DefaultMavenExecutionResult());

        assertTrue(ForkedMavenBuild.isForked(session));
    }

    @Test
    void plainSessionIsNotForked() {
        @SuppressWarnings("deprecation")
        MavenSession session = new MavenSession(null, null, request, new DefaultMavenExecutionResult());

        assertFalse(ForkedMavenBuild.isForked(session));
    }

    @Test
    void extraPropertiesReachTheChild() {
        Properties extra = new Properties();
        extra.setProperty("altDeploymentRepository", "nexus::https://nexus.example.com/repo");
        extra.setProperty("skipTests", "true");

        InvocationRequest req = buildRequest(List.of("clean", "deploy"), extra, false);

        assertEquals("nexus::https://nexus.example.com/repo",
                req.getProperties().getProperty("altDeploymentRepository"));
        assertEquals("true", req.getProperties().getProperty("skipTests"));
    }

    @Test
    void commandLinePropertiesArePropagatedButOursWin() {
        request.getUserProperties().setProperty("spring.profiles.active", "prod");
        request.getUserProperties().setProperty("skipTests", "false");

        Properties extra = new Properties();
        extra.setProperty("skipTests", "true");

        InvocationRequest req = buildRequest(List.of("clean", "install"), extra, true);

        assertEquals("prod", req.getProperties().getProperty("spring.profiles.active"));
        assertEquals("true", req.getProperties().getProperty("skipTests"));
    }

    @Test
    void noModuleSelectionMeansTheWholeReactor() {
        InvocationRequest req = buildRequest(List.of("clean", "install"), null, true);

        assertNull(req.getProjects());
        assertFalse(req.isAlsoMake());
    }

    @Test
    void moduleSelectionIsReproducedInTheChild() {
        request.setSelectedProjects(List.of("service-a"));

        InvocationRequest req = buildRequest(List.of("clean", "deploy"), null, false);

        assertEquals(List.of("service-a"), req.getProjects());
        assertFalse(req.isAlsoMake());
        assertFalse(req.isAlsoMakeDependents());
    }

    @Test
    void makeBehaviourIsPropagated() {
        request.setSelectedProjects(List.of("service-a"));
        request.setMakeBehavior(MavenExecutionRequest.REACTOR_MAKE_BOTH);

        InvocationRequest req = buildRequest(List.of("clean", "deploy"), null, false);

        assertTrue(req.isAlsoMake());
        assertTrue(req.isAlsoMakeDependents());
    }

    @Test
    void dockerBuildForcesAlsoMakeSoSiblingDependenciesCompile() {
        request.setSelectedProjects(List.of("service-a"));

        InvocationRequest req = buildRequest(List.of("clean", "install"), null, true);

        assertTrue(req.isAlsoMake());
    }

    @Test
    void settingsAndOfflineArePropagated() throws IOException {
        File userSettings = root.resolve("settings.xml").toFile();
        Files.writeString(userSettings.toPath(), "<settings/>");
        request.setUserSettingsFile(userSettings);
        request.setOffline(true);
        request.setActiveProfiles(List.of("ci"));

        InvocationRequest req = buildRequest(List.of("clean", "install"), null, true);

        assertEquals(userSettings, req.getUserSettingsFile());
        assertTrue(req.isOffline());
        assertEquals(List.of("ci"), req.getProfiles());
    }
}
