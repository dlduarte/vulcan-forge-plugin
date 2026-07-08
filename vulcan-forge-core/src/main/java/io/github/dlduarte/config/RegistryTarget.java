package io.github.dlduarte.config;

import io.github.dlduarte.ForgeException;

/**
 * Servidor de destino da publicacao. E a <b>unica</b> configuracao que o projeto
 * (pom/gradle) pode sobrescrever; todo o resto e definido globalmente por target.
 */
public enum RegistryTarget {

    /** Sonatype Nexus. */
    NEXUS("nexus", null, null),

    /**
     * GitHub Packages. Docker usa {@code ghcr.io} por padrao (o antigo
     * {@code docker.pkg.github.com} esta deprecado); Maven usa
     * {@code https://maven.pkg.github.com}.
     */
    GITHUB_PACKAGES("github", "ghcr.io", "https://maven.pkg.github.com");

    private final String configKey;
    private final String defaultDockerRegistry;
    private final String defaultMavenBaseUrl;

    RegistryTarget(String configKey, String defaultDockerRegistry, String defaultMavenBaseUrl) {
        this.configKey = configKey;
        this.defaultDockerRegistry = defaultDockerRegistry;
        this.defaultMavenBaseUrl = defaultMavenBaseUrl;
    }

    /** Prefixo usado nas propriedades globais, ex.: {@code vulcanforge.<configKey>.dockerRegistry}. */
    public String configKey() {
        return configKey;
    }

    public String defaultDockerRegistry() {
        return defaultDockerRegistry;
    }

    public String defaultMavenBaseUrl() {
        return defaultMavenBaseUrl;
    }

    public static RegistryTarget from(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        switch (value.trim().toLowerCase()) {
            case "nexus":
                return NEXUS;
            case "github":
            case "github_packages":
            case "github-packages":
            case "ghp":
            case "ghcr":
                return GITHUB_PACKAGES;
            default:
                throw new ForgeException("target invalido: '" + value
                        + "'. Valores aceitos: nexus, github (aliases: ghp, github-packages, ghcr).");
        }
    }
}
