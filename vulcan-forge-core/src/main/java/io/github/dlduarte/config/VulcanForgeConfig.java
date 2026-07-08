package io.github.dlduarte.config;

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

    /** Id das credenciais (server do settings.xml / prefixo em gradle.properties). */
    public String getServerId() {
        return serverId;
    }

    /** Remove a imagem local (tags local e remota) apos o push. Padrao: {@code true}. */
    public boolean isRemoveLocalImage() {
        return removeLocalImage;
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

        public VulcanForgeConfig build() {
            return new VulcanForgeConfig(this);
        }
    }
}
