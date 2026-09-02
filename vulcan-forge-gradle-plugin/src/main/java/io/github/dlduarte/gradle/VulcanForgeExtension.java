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
 *     // skipBuild = true    // nao reconstroi antes do docker build
 * }
 * </pre>
 */
public class VulcanForgeExtension {

    private List<String> enabledGoals;
    private String target;
    private String namespace;
    private String imageName;
    private String tag;
    private String dockerfilePath;
    private Boolean removeLocalImage;
    private java.util.Map<String, String> labels;
    private Boolean ociLabels;
    private Boolean skipBuild;

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

    /**
     * Rotulos a gravar na imagem.
     *
     * <pre>{@code
     * vulcanForge {
     *     labels = ["time": "pagamentos", "tier": "api"]
     * }
     * }</pre>
     *
     * <p>Vencem os automaticos da OCI e os que vierem por {@code vulcanforge.labels}.
     */
    public java.util.Map<String, String> getLabels() {
        return labels;
    }

    public void setLabels(java.util.Map<String, String> labels) {
        this.labels = labels;
    }

    /**
     * Escreve os rotulos padrao da OCI (version, revision, created, source). Padrao:
     * {@code true}.
     *
     * <p>⚠️ Desligar isto tira da imagem a resposta para "de qual commit ela saiu" — e essa
     * resposta nao existe em nenhum outro lugar depois que o pipeline termina. Uma esteira
     * que promova imagem em vez de reconstrui-la depende dela.
     */
    public Boolean getOciLabels() {
        return ociLabels;
    }

    public void setOciLabels(Boolean ociLabels) {
        this.ociLabels = ociLabels;
    }

    public Boolean getRemoveLocalImage() {
        return removeLocalImage;
    }

    public void setRemoveLocalImage(Boolean removeLocalImage) {
        this.removeLocalImage = removeLocalImage;
    }

    /**
     * Nao roda o build previo do artefato: a task {@code dockerPublish} deixa de declarar
     * {@code dependsOn(build)} e {@code dependsOn(clean)}, e a imagem e construida a partir
     * do que ja estiver em {@code build/libs}.
     *
     * <p>E o irmao Gradle do {@code -Dvulcanforge.skipBuild} do plugin Maven, e existe pelo
     * mesmo motivo: num pipeline que ja compilou e CONFERIU o artefato num job anterior,
     * reconstruir aqui publica uma imagem com bytes diferentes dos que foram verificados.
     *
     * <p>Tambem pode vir de {@code -Pvulcanforge.skipBuild=true} ou de
     * {@code VULCANFORGE_SKIP_BUILD=true}, nesta ordem de precedencia (DSL, propriedade,
     * ambiente).
     *
     * <p>⚠️ <b>O que ele substitui:</b> antes da 1.2.0 a unica forma de nao reconstruir era
     * {@code ./gradlew dockerPublish -x build -x clean}. Funciona, e tem uma armadilha —
     * esquecer o {@code -x clean} faz o {@code clean} APAGAR o artefato antes do
     * {@code docker build}, e a falha aparece como um {@code COPY} sem arquivo, sem relacao
     * aparente com a causa.
     */
    public Boolean getSkipBuild() {
        return skipBuild;
    }

    public void setSkipBuild(Boolean skipBuild) {
        this.skipBuild = skipBuild;
    }
}
