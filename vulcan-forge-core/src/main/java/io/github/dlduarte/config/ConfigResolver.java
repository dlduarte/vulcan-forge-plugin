package io.github.dlduarte.config;

import io.github.dlduarte.docker.ImageLabels;

import java.util.LinkedHashMap;
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

    /**
     * Rotulos a gravar na imagem, na forma {@code chave=valor,chave2=valor2}.
     *
     * <p>E a forma de STRING, para quem configura por {@code -D}, {@code gradle.properties} ou
     * variavel de ambiente. No POM e no {@code build.gradle} existe a forma de mapa, que nao
     * tem a limitacao de nao poder conter o separador.
     */
    public static final String KEY_LABELS = "labels";

    /**
     * Escreve os rotulos padrao da OCI. Padrao: {@code true}. Ver
     * {@link io.github.dlduarte.docker.ImageLabels}.
     */
    public static final String KEY_OCI_LABELS = "ociLabels";

    /**
     * Nao roda o build previo do artefato antes de construir a imagem. No Maven e um
     * parametro do mojo ({@code -Dvulcanforge.skipBuild}); no Gradle decide se a task
     * {@code dockerPublish} declara {@code dependsOn(build)}.
     */
    public static final String KEY_SKIP_BUILD = "skipBuild";

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
        return resolve(project, global, defaults, null);
    }

    /**
     * @param projectLabels rotulos na forma de MAPA, vindos do POM ou do {@code build.gradle}.
     *                      Vencem os que chegarem pela chave de string {@code labels}, porque
     *                      sao a configuracao mais especifica que existe: escrita naquele
     *                      projeto, naquele arquivo.
     */
    public VulcanForgeConfig resolve(Map<String, String> project,
                                     Map<String, String> global,
                                     Map<String, String> defaults,
                                     Map<String, String> projectLabels) {
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

        // ociLabels: projeto > global > default true
        String ociLabels = firstNonBlank(
                get(project, KEY_OCI_LABELS), get(global, KEY_OCI_LABELS));

        // Rotulos: acumulam, do mais generico para o mais especifico. Nao substituem --
        // um rotulo de organizacao no global e um de time no projeto convivem, e so
        // colidem se usarem a MESMA chave, caso em que o mais especifico vence.
        Map<String, String> labels = new LinkedHashMap<>();
        labels.putAll(ImageLabels.parse(get(global, KEY_LABELS)));
        labels.putAll(ImageLabels.parse(get(project, KEY_LABELS)));
        if (projectLabels != null) {
            labels.putAll(projectLabels);
        }

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
                .labels(labels)
                .ociLabels(ociLabels == null || Boolean.parseBoolean(ociLabels))
                .build();
    }

    /**
     * Todas as chaves de configuracao que fazem sentido para um dado target, sem o prefixo
     * {@code vulcanforge.}.
     *
     * <p>Existe para quem precisa VARRER as chaves em vez de perguntar por uma —
     * tipicamente para descobrir quais tem valor no ambiente. Como as chaves por target sao
     * montadas ({@code <target>.dockerRegistry}), ela e a unica lista completa; espalhar
     * essa montagem por quem le seria a mesma regra em dois lugares.
     */
    public static java.util.List<String> configKeys(RegistryTarget target) {
        String tp = target.configKey() + ".";
        return java.util.List.of(
                KEY_TARGET, KEY_NAMESPACE, KEY_IMAGE_NAME, KEY_TAG,
                KEY_DOCKERFILE_PATH, KEY_REMOVE_LOCAL_IMAGE, KEY_SKIP_BUILD,
                KEY_LABELS, KEY_OCI_LABELS,
                tp + SUB_DOCKER_REGISTRY, tp + SUB_MAVEN_URL,
                tp + SUB_NAMESPACE, tp + SUB_SERVER_ID);
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
