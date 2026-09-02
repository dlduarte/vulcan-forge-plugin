package io.github.dlduarte.config;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Configuracao resolvida (imutavel) para um target especifico, apos aplicar a config
 * global e os defaults do projeto. Produzida por {@link ConfigResolver}.
 */
public final class VulcanForgeConfig {

    private final RegistryTarget target;
    private final String dockerRegistry;
    private final String mavenUrl;
    private final String namespace;
    private final String imageName;
    private final String tag;
    private final String dockerfilePath;
    private final String serverId;
    private final boolean removeLocalImage;
    private final Map<String, String> labels;
    private final boolean ociLabels;

    private VulcanForgeConfig(Builder b) {
        this.target = b.target;
        this.dockerRegistry = b.dockerRegistry;
        this.mavenUrl = b.mavenUrl;
        this.namespace = b.namespace;
        this.imageName = b.imageName;
        this.tag = b.tag;
        this.dockerfilePath = b.dockerfilePath;
        this.serverId = b.serverId;
        this.removeLocalImage = b.removeLocalImage;
        this.labels = Collections.unmodifiableMap(new LinkedHashMap<>(b.labels));
        this.ociLabels = b.ociLabels;
    }

    public RegistryTarget getTarget() {
        return target;
    }

    /** Host do registry Docker (ex.: {@code nexus.example.com:8083}, {@code ghcr.io}). */
    public String getDockerRegistry() {
        return dockerRegistry;
    }

    /** URL do repositorio Maven de destino. */
    public String getMavenUrl() {
        return mavenUrl;
    }

    public String getNamespace() {
        return namespace;
    }

    public String getImageName() {
        return imageName;
    }

    public String getTag() {
        return tag;
    }

    public String getDockerfilePath() {
        return dockerfilePath;
    }

    /** Id das credenciais (server do settings.xml / prefixo em gradle.properties e no ambiente). */
    public String getServerId() {
        return serverId;
    }

    /** Remove a imagem local (tags local e remota) apos o push. Padrao: {@code true}. */
    public boolean isRemoveLocalImage() {
        return removeLocalImage;
    }

    /**
     * Rotulos explicitos, na ordem de declaracao. Nunca {@code null}.
     *
     * <p>Eles VENCEM os automaticos da OCI: quem configurou um valor a mao disse o que queria.
     */
    public Map<String, String> getLabels() {
        return labels;
    }

    /**
     * Escreve os rotulos padrao da OCI (version, revision, created, source). Padrao:
     * {@code true}.
     *
     * <p>Ligado por padrao de proposito. Configuracao que precisa ser lembrada e configuracao
     * que sera esquecida em algum projeto — e o projeto que a esquecer so descobre quando
     * alguem precisar da resposta que a imagem nao tem. Ver
     * {@link io.github.dlduarte.docker.ImageLabels}.
     */
    public boolean isOciLabels() {
        return ociLabels;
    }

    public static Builder builder() {
        return new Builder();
    }

    @Override
    public String toString() {
        return "VulcanForgeConfig{"
                + "target=" + target
                + ", dockerRegistry='" + dockerRegistry + '\''
                + ", mavenUrl='" + mavenUrl + '\''
                + ", namespace='" + namespace + '\''
                + ", imageName='" + imageName + '\''
                + ", tag='" + tag + '\''
                + ", dockerfilePath='" + dockerfilePath + '\''
                + ", serverId='" + serverId + '\''
                + ", removeLocalImage=" + removeLocalImage
                + ", labels=" + labels
                + ", ociLabels=" + ociLabels
                + '}';
    }

    public static final class Builder {
        private RegistryTarget target;
        private String dockerRegistry;
        private String mavenUrl;
        private String namespace;
        private String imageName;
        private String tag;
        private String dockerfilePath;
        private String serverId;
        private boolean removeLocalImage = true;
        private Map<String, String> labels = new LinkedHashMap<>();
        private boolean ociLabels = true;

        public Builder target(RegistryTarget v) {
            this.target = v;
            return this;
        }

        public Builder dockerRegistry(String v) {
            this.dockerRegistry = v;
            return this;
        }

        public Builder mavenUrl(String v) {
            this.mavenUrl = v;
            return this;
        }

        public Builder namespace(String v) {
            this.namespace = v;
            return this;
        }

        public Builder imageName(String v) {
            this.imageName = v;
            return this;
        }

        public Builder tag(String v) {
            this.tag = v;
            return this;
        }

        public Builder dockerfilePath(String v) {
            this.dockerfilePath = v;
            return this;
        }

        public Builder serverId(String v) {
            this.serverId = v;
            return this;
        }

        public Builder removeLocalImage(boolean v) {
            this.removeLocalImage = v;
            return this;
        }

        public Builder labels(Map<String, String> v) {
            this.labels = v == null ? new LinkedHashMap<>() : new LinkedHashMap<>(v);
            return this;
        }

        public Builder ociLabels(boolean v) {
            this.ociLabels = v;
            return this;
        }

        public VulcanForgeConfig build() {
            return new VulcanForgeConfig(this);
        }
    }
}
