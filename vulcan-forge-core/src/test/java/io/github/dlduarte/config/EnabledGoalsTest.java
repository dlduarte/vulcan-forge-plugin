package io.github.dlduarte.config;

import io.github.dlduarte.ForgeException;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class EnabledGoalsTest {

    @Test
    void goalDeclaradoPassaEOsDemaisNao() {
        EnabledGoals goals = EnabledGoals.parse(List.of("maven-publish"), BuildTool.MAVEN);

        assertTrue(goals.isDeclared());
        assertDoesNotThrow(() -> goals.require(ForgeGoal.MAVEN));

        ForgeException e = assertThrows(ForgeException.class, () -> goals.require(ForgeGoal.DOCKER));
        assertTrue(e.getMessage().contains("docker-publish"));
        assertTrue(e.getMessage().contains("<enabledGoals>"));
    }

    @Test
    void semDeclaracaoNenhumGoalRoda() {
        for (List<String> raw : Arrays.asList(null, Collections.<String>emptyList(), List.of("", "  "))) {
            EnabledGoals goals = EnabledGoals.parse(raw, BuildTool.MAVEN);
            assertFalse(goals.isDeclared());
            for (ForgeGoal goal : ForgeGoal.values()) {
                ForgeException e = assertThrows(ForgeException.class, () -> goals.require(goal));
                // a mensagem ensina a declarar, listando tudo que existe (a escolha e do dev)
                assertTrue(e.getMessage().contains("<goal>docker-publish</goal>"));
                assertTrue(e.getMessage().contains("<goal>maven-publish</goal>"));
            }
        }
    }

    @Test
    void semDeclaracaoNoGradleAMensagemUsaADsl() {
        EnabledGoals goals = EnabledGoals.parse(null, BuildTool.GRADLE);

        ForgeException e = assertThrows(ForgeException.class, () -> goals.require(ForgeGoal.DOCKER));
        assertTrue(e.getMessage().contains("vulcanForge {"));
        assertTrue(e.getMessage().contains("enabledGoals = ['dockerPublish', 'vulcanMavenPublish']"));
        assertFalse(e.getMessage().contains("pom.xml"));
    }

    @Test
    void mensagensUsamOVocabularioDoGradle() {
        EnabledGoals goals = EnabledGoals.parse(List.of("dockerPublish"), BuildTool.GRADLE);

        ForgeException e = assertThrows(ForgeException.class, () -> goals.require(ForgeGoal.MAVEN));
        assertTrue(e.getMessage().contains("vulcanMavenPublish"));
        assertTrue(e.getMessage().contains("enabledGoals"));
        assertFalse(e.getMessage().contains("<goal>"));
        assertEquals("dockerPublish", goals.describe());
    }

    @Test
    void aceitaNomeMavenNomeGradleEApelido() {
        for (String value : List.of("docker-publish", "dockerPublish", "docker", "DOCKER", "docker_publish")) {
            assertEquals(ForgeGoal.DOCKER, ForgeGoal.from(value), value);
        }
        for (String value : List.of("maven-publish", "vulcanMavenPublish", "maven", "Maven-Publish")) {
            assertEquals(ForgeGoal.MAVEN, ForgeGoal.from(value), value);
        }
    }

    @Test
    void goalDesconhecidoFalhaListandoOsValidos() {
        ForgeException e = assertThrows(ForgeException.class,
                () -> EnabledGoals.parse(List.of("deploy"), BuildTool.MAVEN));
        assertTrue(e.getMessage().contains("deploy"));
        assertTrue(e.getMessage().contains("docker-publish"));
        assertTrue(e.getMessage().contains("maven-publish"));
    }
}
