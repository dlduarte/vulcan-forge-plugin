package io.github.dlduarte.gradle;

/**
 * DSL do plugin Gradle. As coordenadas do servidor sao globais (gradle.properties); aqui
 * o projeto ajusta apenas a <b>identidade</b> do artefato — principalmente o servidor
 * ({@code target}) e o {@code namespace} (que pode variar por projeto):
 *
 * <pre>
 * vulcanForge {
 *     target = "github"        // nexus | github
 *     namespace = "minha-org"  // owner do ghcr.io, por exemplo
 *     // imageName, tag, dockerfilePath tambem podem ser sobrescritos
 * }
 * </pre>
 */
public class VulcanForgeExtension {

    private String target;
    private String namespace;
    private String imageName;
    private String tag;
    private String dockerfilePath;
    private Boolean removeLocalImage;

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
}
