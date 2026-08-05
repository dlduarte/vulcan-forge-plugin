package io.github.dlduarte.maven;

import io.github.dlduarte.ForgeException;
import io.github.dlduarte.config.BuildTool;
import io.github.dlduarte.config.EnabledGoals;
import org.apache.maven.project.MavenProject;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.function.Function;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Selecao de modulos num reator tipico: um parent agregador, dois servicos com Dockerfile
 * e uma lib interna sem Dockerfile.
 */
class ReactorModulesTest {

    private static final Function<MavenProject, String> DEFAULT_PATH = p -> "Dockerfile";

    /** O parent declarou os dois goals e nenhum modulo restringiu. */
    private static final Function<MavenProject, EnabledGoals> ALL_ENABLED =
            p -> EnabledGoals.parse(List.of("docker-publish", "maven-publish"), BuildTool.MAVEN);

    /** Le o {@code vulcanforge.enabledGoals} das {@code <properties>} de cada modulo. */
    private static Function<MavenProject, EnabledGoals> declaredPerModule() {
        return p -> EnabledGoals.parse(
                ReactorModules.parseList(p.getProperties().getProperty("vulcanforge.enabledGoals")),
                BuildTool.MAVEN);
    }

    @TempDir
    Path root;

    private MavenProject parent;
    private MavenProject serviceA;
    private MavenProject serviceB;
    private MavenProject commons;

    @BeforeEach
    void setUp() throws IOException {
        parent = module("parent", "pom", false);
        serviceA = module("service-a", "jar", true);
        serviceB = module("service-b", "jar", true);
        commons = module("commons", "jar", false);
    }

    private MavenProject module(String artifactId, String packaging, boolean withDockerfile) throws IOException {
        Path dir = Files.createDirectories(root.resolve(artifactId));
        Files.writeString(dir.resolve("pom.xml"), "<project/>");
        if (withDockerfile) {
            Files.writeString(dir.resolve("Dockerfile"), "FROM eclipse-temurin:17-jre");
        }
        MavenProject project = new MavenProject();
        project.setArtifactId(artifactId);
        project.setVersion("1.0.0-SNAPSHOT");
        project.setPackaging(packaging);
        project.setFile(dir.resolve("pom.xml").toFile());
        return project;
    }

    private static List<String> artifactIds(List<MavenProject> projects) {
        return projects.stream().map(MavenProject::getArtifactId).collect(Collectors.toList());
    }

    @Test
    void selectsOnlyModulesWithDockerfile() {
        List<MavenProject> selected = ReactorModules.selectForDocker(
                List.of(parent, serviceA, serviceB, commons), DEFAULT_PATH, ALL_ENABLED, List.of());

        assertEquals(List.of("service-a", "service-b"), artifactIds(selected));
    }

    @Test
    void keepsReactorOrder() {
        List<MavenProject> selected = ReactorModules.selectForDocker(
                List.of(parent, serviceB, serviceA), DEFAULT_PATH, ALL_ENABLED, List.of());

        assertEquals(List.of("service-b", "service-a"), artifactIds(selected));
    }

    @Test
    void moduleOptsOutWithSkipProperty() {
        serviceB.getProperties().setProperty(ReactorModules.SKIP_PROPERTY, "true");

        List<MavenProject> selected = ReactorModules.selectForDocker(
                List.of(parent, serviceA, serviceB), DEFAULT_PATH, ALL_ENABLED, List.of());

        assertEquals(List.of("service-a"), artifactIds(selected));
    }

    @Test
    void explicitModulesRestrictSelection() {
        List<MavenProject> selected = ReactorModules.selectForDocker(
                List.of(parent, serviceA, serviceB), DEFAULT_PATH, ALL_ENABLED, List.of("service-b"));

        assertEquals(List.of("service-b"), artifactIds(selected));
    }

    @Test
    void explicitUnknownModuleFails() {
        ForgeException e = assertThrows(ForgeException.class, () -> ReactorModules.selectForDocker(
                List.of(parent, serviceA), DEFAULT_PATH, ALL_ENABLED, List.of("service-z")));

        assertTrue(e.getMessage().contains("service-z"), e.getMessage());
        assertTrue(e.getMessage().contains("service-a"), e.getMessage());
    }

    @Test
    void honoursPerModuleDockerfilePath() throws IOException {
        Files.writeString(root.resolve("commons").resolve("Dockerfile.custom"),
                "FROM eclipse-temurin:17-jre");

        Function<MavenProject, String> perModule =
                p -> "commons".equals(p.getArtifactId()) ? "Dockerfile.custom" : "Dockerfile";

        List<MavenProject> selected = ReactorModules.selectForDocker(
                List.of(parent, serviceA, commons), perModule, ALL_ENABLED, List.of());

        assertEquals(List.of("service-a", "commons"), artifactIds(selected));
    }

    @Test
    void emptySelectionExplainsWhatWasSkipped() {
        commons.getProperties().setProperty(ReactorModules.SKIP_PROPERTY, "true");

        ForgeException e = assertThrows(ForgeException.class, () -> ReactorModules.selectForDocker(
                List.of(parent, commons, serviceA), p -> "Dockerfile.missing", ALL_ENABLED, List.of()));

        assertTrue(e.getMessage().contains("Sem Dockerfile"), e.getMessage());
        assertTrue(e.getMessage().contains("service-a"), e.getMessage());
        assertTrue(e.getMessage().contains(ReactorModules.SKIP_PROPERTY), e.getMessage());
    }

    @Test
    void singleModuleProjectStillWorks() {
        List<MavenProject> selected = ReactorModules.selectForDocker(
                List.of(serviceA), DEFAULT_PATH, ALL_ENABLED, List.of());

        assertEquals(List.of("service-a"), artifactIds(selected));
    }

    @Test
    void absoluteDockerfilePathIsHonoured() throws IOException {
        File shared = root.resolve("Dockerfile.shared").toFile();
        Files.writeString(shared.toPath(), "FROM eclipse-temurin:17-jre");

        List<MavenProject> selected = ReactorModules.selectForDocker(
                List.of(parent, commons), p -> shared.getAbsolutePath(), ALL_ENABLED, List.of());

        assertEquals(List.of("commons"), artifactIds(selected));
    }

    @Test
    void parseListTrimsAndDropsEmpties() {
        assertEquals(List.of("a", "b"), ReactorModules.parseList(" a , , b "));
        assertEquals(List.of(), ReactorModules.parseList("  "));
        assertEquals(List.of(), ReactorModules.parseList(null));
    }

    // ---- <enabledGoals> por modulo ----

    @Test
    void moduleWithDockerfileButWithoutTheGoalIsNotPublished() {
        serviceA.getProperties().setProperty("vulcanforge.enabledGoals", "docker-publish");
        serviceB.getProperties().setProperty("vulcanforge.enabledGoals", "maven-publish");

        List<MavenProject> selected = ReactorModules.selectForDocker(
                List.of(parent, serviceA, serviceB), DEFAULT_PATH, declaredPerModule(), List.of());

        assertEquals(List.of("service-a"), artifactIds(selected));
    }

    @Test
    void moduleThatDeclaresNothingIsNotPublished() {
        serviceA.getProperties().setProperty("vulcanforge.enabledGoals", "docker-publish");

        List<MavenProject> selected = ReactorModules.selectForDocker(
                List.of(parent, serviceA, serviceB), DEFAULT_PATH, declaredPerModule(), List.of());

        assertEquals(List.of("service-a"), artifactIds(selected));
    }

    @Test
    void goalAliasesAreAccepted() {
        serviceA.getProperties().setProperty("vulcanforge.enabledGoals", "dockerPublish");
        serviceB.getProperties().setProperty("vulcanforge.enabledGoals", "docker, maven");

        List<MavenProject> selected = ReactorModules.selectForDocker(
                List.of(parent, serviceA, serviceB), DEFAULT_PATH, declaredPerModule(), List.of());

        assertEquals(List.of("service-a", "service-b"), artifactIds(selected));
    }

    @Test
    void emptySelectionNamesTheModulesMissingTheGoal() {
        ForgeException e = assertThrows(ForgeException.class, () -> ReactorModules.selectForDocker(
                List.of(parent, serviceA, serviceB), DEFAULT_PATH, declaredPerModule(), List.of()));

        assertTrue(e.getMessage().contains("<enabledGoals>"), e.getMessage());
        assertTrue(e.getMessage().contains("service-a"), e.getMessage());
        assertTrue(e.getMessage().contains("service-b"), e.getMessage());
    }

    // ---- modulos que o 'clean deploy' vai publicar ----

    @Test
    void everyModuleIsDeployableByDefault() {
        assertEquals(List.of("parent", "commons", "service-a"),
                artifactIds(ReactorModules.deployable(List.of(parent, commons, serviceA))));
    }

    @Test
    void mavenDeploySkipTakesTheModuleOutOfTheDeploy() {
        serviceA.getProperties().setProperty(ReactorModules.DEPLOY_SKIP_PROPERTY, "true");

        assertEquals(List.of("parent", "commons"),
                artifactIds(ReactorModules.deployable(List.of(parent, commons, serviceA))));
    }
}
