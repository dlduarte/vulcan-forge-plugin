package io.github.dlduarte.gradle;

import java.util.List;

/**
 * DSL do plugin Gradle. As coordenadas do servidor sao globais (gradle.properties); aqui
 * o projeto ajusta apenas a <b>identidade</b> do artefato — principalmente o servidor
 * ({@code target}) e o {@code namespace} (que pode variar por projeto) — e declara quais
 * tasks do plugin ele pode rodar ({@code enabledGoals}, obrigatorio):
 *
 * <pre>
 * vulcanForge {
 *     enabledGoals = ['vulcanMavenPublish']  // dockerPublish e/ou vulcanMavenPublish
 *     target = "github"        // nexus | github
 *     namespace = "minha-org"  // owner do ghcr.io, por exemplo
 *     // imageName, tag, dockerfilePath tambem podem ser sobrescritos
 * }
 * </pre>
 *
 * <p>Num build multi-projeto, um subproject pode se excluir da publicacao com
 * {@code vulcanForge { skip = true }} (equivalente ao {@code vulcanforge.skip} do Maven).
 */
public class VulcanForgeExtension {

    private List<String> enabledGoals;
    private String target;
    private String namespace;
    private String imageName;
    private String tag;
    private String dockerfilePath;
    private Boolean removeLocalImage;
    private boolean skip;

    /**
     * Tasks do Vulcan Forge habilitadas neste projeto ({@code dockerPublish},
     * {@code vulcanMavenPublish}). Obrigatorio: as nao declaradas somem do grupo de tasks
     * e falham se forem invocadas.
     */
    public List<String> getEnabledGoals() {
        return enabledGoals;
    }

    public void setEnabledGoals(List<String> enabledGoals) {
        this.enabledGoals = enabledGoals;
    }

    public String getTarget() {
        return target;
    }

    public void setTarget(String target) {
        this.target = target;
    }

    public String getNamespace() {
        return namespace;
    }

    public void setNamespace(String namespace) {
        this.namespace = namespace;
    }

    public String getImageName() {
        return imageName;
    }

    public void setImageName(String imageName) {
        this.imageName = imageName;
    }

    public String getTag() {
        return tag;
    }

    public void setTag(String tag) {
        this.tag = tag;
    }

    public String getDockerfilePath() {
        return dockerfilePath;
    }

    public void setDockerfilePath(String dockerfilePath) {
        this.dockerfilePath = dockerfilePath;
    }

    public Boolean getRemoveLocalImage() {
        return removeLocalImage;
    }

    public void setRemoveLocalImage(Boolean removeLocalImage) {
        this.removeLocalImage = removeLocalImage;
    }

    public boolean isSkip() {
        return skip;
    }

    public void setSkip(boolean skip) {
        this.skip = skip;
    }
}
