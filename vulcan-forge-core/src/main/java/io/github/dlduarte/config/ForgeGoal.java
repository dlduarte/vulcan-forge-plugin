package io.github.dlduarte.config;

import io.github.dlduarte.ForgeException;

import java.util.Locale;

/**
 * Publicacao suportada pelo plugin, com o nome que ela tem em cada build tool.
 *
 * <p>Cada projeto declara quais goals estao habilitados (ver {@link EnabledGoals}); os
 * demais se recusam a rodar. Isso evita, por exemplo, que um projeto que so distribui o
 * jar acabe gerando e publicando uma imagem Docker por engano.
 */
public enum ForgeGoal {

    DOCKER("docker-publish", "dockerPublish", "publica a imagem Docker"),

    MAVEN("maven-publish", "vulcanMavenPublish", "publica o pacote Maven (jar/pom)");

    private final String mavenGoal;
    private final String gradleTask;
    private final String description;

    ForgeGoal(String mavenGoal, String gradleTask, String description) {
        this.mavenGoal = mavenGoal;
        this.gradleTask = gradleTask;
        this.description = description;
    }

    /** Nome do goal no Maven, ex.: {@code docker-publish}. */
    public String mavenGoal() {
        return mavenGoal;
    }

    /** Nome da task no Gradle, ex.: {@code dockerPublish}. */
    public String gradleTask() {
        return gradleTask;
    }

    /** O que a publicacao faz (usado nas mensagens de ajuda). */
    public String description() {
        return description;
    }

    /** Nome deste goal na build tool informada. */
    public String nameIn(BuildTool tool) {
        return tool == BuildTool.GRADLE ? gradleTask : mavenGoal;
    }

    /**
     * Resolve um valor declarado pelo projeto. Aceita o nome do goal Maven, o da task
     * Gradle ou o apelido curto ({@code docker} / {@code maven}), sem diferenciar
     * maiusculas, hifens e underscores — assim a mesma declaracao serve para os dois mundos.
     *
     * @throws ForgeException se o valor nao corresponder a nenhum goal
     */
    public static ForgeGoal from(String value) {
        String normalized = value == null ? "" : value.trim().toLowerCase(Locale.ROOT)
                .replace("-", "").replace("_", "");
        switch (normalized) {
            case "docker":
            case "dockerpublish":
                return DOCKER;
            case "maven":
            case "mavenpublish":
            case "vulcanmavenpublish":
                return MAVEN;
            default:
                throw new ForgeException("vulcan-forge: goal desconhecido: '" + value + "'. "
                        + "Valores aceitos: " + DOCKER.mavenGoal + " (ou " + DOCKER.gradleTask + ", docker), "
                        + MAVEN.mavenGoal + " (ou " + MAVEN.gradleTask + ", maven).");
        }
    }
}
