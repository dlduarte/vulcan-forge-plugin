package io.github.dlduarte.config;

import java.util.Map;

/**
 * Resolve a {@link VulcanForgeConfig}.
 *
 * <p>Modelo: as <b>coordenadas do servidor</b> (dockerRegistry, mavenUrl, serverId) sao
 * sempre globais e organizadas por <b>target</b> (nexus/github) — settings.xml (Maven) ou
 * {@code ~/.gradle/gradle.properties} (Gradle). O projeto pode sobrescrever apenas a
 * <b>identidade</b> do artefato: {@code target}, {@code namespace}, {@code imageName},
 * {@code tag}, {@code dockerfilePath}.
 *
 * <p>Chaves globais (prefixo {@code vulcanforge.}):
 * <ul>
 *   <li>{@code target} — target default (nexus|github);</li>
 *   <li>{@code dockerfilePath}, {@code imageName}, {@code tag} — comuns (opcionais);</li>
 *   <li>{@code <target>.dockerRegistry}, {@code <target>.mavenUrl},
 *       {@code <target>.namespace}, {@code <target>.serverId} — por target.</li>
 * </ul>
 */
public class ConfigResolver {

    public static final String PROPERTY_PREFIX = "vulcanforge.";

    // Chaves de identidade (podem vir do projeto ou do global comum).
    public static final String KEY_TARGET = "target";
    public static final String KEY_NAMESPACE = "namespace";
    public static final String KEY_IMAGE_NAME = "imageName";
    public static final String KEY_TAG = "tag";
    public static final String KEY_DOCKERFILE_PATH = "dockerfilePath";
    public static final String KEY_REMOVE_LOCAL_IMAGE = "removeLocalImage";

    // Subchaves por target: vulcanforge.<target>.<sub>
    public static final String SUB_DOCKER_REGISTRY = "dockerRegistry";
    public static final String SUB_MAVEN_URL = "mavenUrl";
    public static final String SUB_NAMESPACE = "namespace";
    public static final String SUB_SERVER_ID = "serverId";

    /**
     * @param project  overrides de identidade do projeto (target, namespace, imageName,
     *                 tag, dockerfilePath); pode ser {@code null}/vazio
     * @param global   mapa global (chaves sem o prefixo {@code vulcanforge.})
     * @param defaults defaults dinamicos do projeto (imageName, tag)
     */
    public VulcanForgeConfig resolve(Map<String, String> project,
                                     Map<String, String> global,
                                     Map<String, String> defaults) {
        String targetValue = firstNonBlank(
                get(project, KEY_TARGET),
                get(global, KEY_TARGET),
                "nexus");
        RegistryTarget target = RegistryTarget.from(targetValue);

        String tp = target.configKey() + ".";

        // namespace: projeto > bloco do target no global
        String namespace = firstNonBlank(
                get(project, KEY_NAMESPACE),
                get(global, tp + SUB_NAMESPACE));

        String dockerRegistry = get(global, tp + SUB_DOCKER_REGISTRY);
        if (dockerRegistry == null && target.defaultDockerRegistry() != null) {
            dockerRegistry = target.defaultDockerRegistry();
        }

        String mavenUrl = get(global, tp + SUB_MAVEN_URL);
        if (mavenUrl == null && target.defaultMavenBaseUrl() != null && namespace != null) {
            mavenUrl = target.defaultMavenBaseUrl() + "/" + namespace;
        }

        String imageName = firstNonBlank(
                get(project, KEY_IMAGE_NAME), get(global, KEY_IMAGE_NAME), get(defaults, KEY_IMAGE_NAME));
        String tag = firstNonBlank(
                get(project, KEY_TAG), get(global, KEY_TAG), get(defaults, KEY_TAG), "latest");
        String dockerfilePath = firstNonBlank(
                get(project, KEY_DOCKERFILE_PATH), get(global, KEY_DOCKERFILE_PATH), "Dockerfile");

        // removeLocalImage: projeto > global > default true
        String removeLocalImage = firstNonBlank(
                get(project, KEY_REMOVE_LOCAL_IMAGE), get(global, KEY_REMOVE_LOCAL_IMAGE));

        return VulcanForgeConfig.builder()
                .target(target)
                .dockerRegistry(dockerRegistry)
                .mavenUrl(mavenUrl)
                .namespace(namespace)
                .imageName(imageName)
                .tag(tag)
                .dockerfilePath(dockerfilePath)
                .serverId(get(global, tp + SUB_SERVER_ID))
                .removeLocalImage(removeLocalImage == null || Boolean.parseBoolean(removeLocalImage))
                .build();
    }

    private static String firstNonBlank(String... values) {
        for (String v : values) {
            if (v != null && !v.isBlank()) {
                return v.trim();
            }
        }
        return null;
    }

    private static String get(Map<String, String> map, String key) {
        if (map == null) {
            return null;
        }
        String v = map.get(key);
        return (v == null || v.isBlank()) ? null : v.trim();
    }
}
