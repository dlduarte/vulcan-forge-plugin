package io.github.dlduarte.config;

import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class ConfigResolverTest {

    private final ConfigResolver resolver = new ConfigResolver();

    private Map<String, String> globalWithBothTargets() {
        Map<String, String> g = new HashMap<>();
        g.put("target", "nexus");
        g.put("nexus.dockerRegistry", "nexus.example.com:8083");
        g.put("nexus.mavenUrl", "https://nexus.example.com/repository/maven-releases");
        g.put("nexus.namespace", "meu-time");
        g.put("nexus.serverId", "nexus-docker");
        g.put("github.namespace", "minha-org");
        g.put("github.serverId", "github-token");
        return g;
    }

    @Test
    void defaultsToNexusWhenNothingConfigured() {
        VulcanForgeConfig cfg = resolver.resolve(Map.of(), Map.of(), Map.of());

        assertEquals(RegistryTarget.NEXUS, cfg.getTarget());
        assertEquals("latest", cfg.getTag());
        assertEquals("Dockerfile", cfg.getDockerfilePath());
        assertNull(cfg.getImageName());
        assertNull(cfg.getDockerRegistry());
    }

    @Test
    void readsTargetSpecificGlobalConfig() {
        VulcanForgeConfig cfg = resolver.resolve(Map.of(), globalWithBothTargets(), Map.of());

        assertEquals(RegistryTarget.NEXUS, cfg.getTarget());
        assertEquals("nexus.example.com:8083", cfg.getDockerRegistry());
        assertEquals("https://nexus.example.com/repository/maven-releases", cfg.getMavenUrl());
        assertEquals("meu-time", cfg.getNamespace());
        assertEquals("nexus-docker", cfg.getServerId());
    }

    @Test
    void projectSelectsTarget() {
        // Global default e nexus; o projeto escolhe github -> puxa o bloco github.
        VulcanForgeConfig cfg = resolver.resolve(
                Map.of("target", "github"), globalWithBothTargets(), Map.of());

        assertEquals(RegistryTarget.GITHUB_PACKAGES, cfg.getTarget());
        assertEquals("minha-org", cfg.getNamespace());
        assertEquals("github-token", cfg.getServerId());
        // defaults do github
        assertEquals("ghcr.io", cfg.getDockerRegistry());
        assertEquals("https://maven.pkg.github.com/minha-org", cfg.getMavenUrl());
    }

    @Test
    void projectOverridesNamespacePerProject() {
        // serverId/registry continuam do global; so o namespace muda por projeto.
        VulcanForgeConfig cfg = resolver.resolve(
                Map.of("target", "github", "namespace", "personal-owner"),
                globalWithBothTargets(), Map.of());

        assertEquals(RegistryTarget.GITHUB_PACKAGES, cfg.getTarget());
        assertEquals("personal-owner", cfg.getNamespace());
        assertEquals("github-token", cfg.getServerId());       // global
        assertEquals("ghcr.io", cfg.getDockerRegistry());       // global/default
        assertEquals("https://maven.pkg.github.com/personal-owner", cfg.getMavenUrl());
    }

    @Test
    void ghpAliasSelectsGithubTarget() {
        VulcanForgeConfig cfg = resolver.resolve(
                Map.of("target", "ghp"), globalWithBothTargets(), Map.of());
        assertEquals(RegistryTarget.GITHUB_PACKAGES, cfg.getTarget());
        assertEquals("minha-org", cfg.getNamespace());
        assertEquals("ghcr.io", cfg.getDockerRegistry());
    }

    @Test
    void githubDockerRegistryDefaultsToGhcr() {
        Map<String, String> g = new HashMap<>();
        g.put("target", "github");
        g.put("github.namespace", "acme");

        VulcanForgeConfig cfg = resolver.resolve(Map.of(), g, Map.of());
        assertEquals("ghcr.io", cfg.getDockerRegistry());
        assertEquals("https://maven.pkg.github.com/acme", cfg.getMavenUrl());
    }

    @Test
    void imageNameAndTagFromDefaultsUnlessGlobalSetsThem() {
        Map<String, String> defaults = Map.of("imageName", "artifact-id", "tag", "1.0.0");

        VulcanForgeConfig cfg = resolver.resolve(Map.of(), Map.of(), defaults);
        assertEquals("artifact-id", cfg.getImageName());
        assertEquals("1.0.0", cfg.getTag());

        Map<String, String> global = Map.of("tag", "edge");
        VulcanForgeConfig cfg2 = resolver.resolve(Map.of(), global, defaults);
        assertEquals("edge", cfg2.getTag()); // global vence o default
        assertEquals("artifact-id", cfg2.getImageName());
    }
}
