package io.github.dlduarte.gradle;

import io.github.dlduarte.config.ConfigResolver;
import io.github.dlduarte.config.EnvConfigSource;
import io.github.dlduarte.config.RegistryTarget;
import io.github.dlduarte.config.VulcanForgeConfig;
import org.gradle.api.Project;

import java.util.HashMap;
import java.util.Map;

/**
 * Monta a {@link VulcanForgeConfig} do Gradle.
 *
 * <p>As coordenadas do servidor sao globais — propriedades {@code vulcanforge.*}, tipicamente
 * em {@code ~/.gradle/gradle.properties}, o equivalente ao {@code settings.xml} do Maven — e
 * o projeto sobrescreve apenas a identidade do artefato pela DSL {@code vulcanForge { ... }}.
 *
 * <p><b>Desde a 1.2.0 o ambiente tambem e uma fonte</b>, atras das propriedades: o que estiver
 * em {@code VULCANFORGE_*} preenche o que nao foi declarado. E o que permite um pipeline
 * configurar o plugin sem escrever nada em disco nem passar valor pela linha de comando. A
 * regra de nome e a precedencia estao em {@link EnvConfigSource}.
 */
final class GradleConfigReader {

    private GradleConfigReader() {
    }

    static VulcanForgeConfig resolve(Project project, VulcanForgeExtension ext) {
        return resolve(project, ext, new EnvConfigSource());
    }

    /** Sobrecarga com o ambiente injetado — e o que torna esta classe testavel. */
    static VulcanForgeConfig resolve(Project project, VulcanForgeExtension ext, EnvConfigSource env) {
        Map<String, String> global = new HashMap<>();
        for (Map.Entry<String, ?> entry : project.getProperties().entrySet()) {
            String name = entry.getKey();
            Object value = entry.getValue();
            if (name.startsWith(ConfigResolver.PROPERTY_PREFIX) && value != null
                    && !value.toString().isBlank()) {
                String key = name.substring(ConfigResolver.PROPERTY_PREFIX.length());
                global.put(key, value.toString().trim());
            }
        }

        Map<String, String> projectOverrides = new HashMap<>();
        put(projectOverrides, ConfigResolver.KEY_TARGET, ext.getTarget());
        put(projectOverrides, ConfigResolver.KEY_NAMESPACE, ext.getNamespace());
        put(projectOverrides, ConfigResolver.KEY_IMAGE_NAME, ext.getImageName());
        put(projectOverrides, ConfigResolver.KEY_TAG, ext.getTag());
        put(projectOverrides, ConfigResolver.KEY_DOCKERFILE_PATH, ext.getDockerfilePath());
        if (ext.getRemoveLocalImage() != null) {
            projectOverrides.put(ConfigResolver.KEY_REMOVE_LOCAL_IMAGE,
                    String.valueOf(ext.getRemoveLocalImage()));
        }

        // As chaves POR TARGET (`<target>.dockerRegistry`, ...) so podem ser procuradas no
        // ambiente depois de se saber qual e o target -- e ele proprio pode vir de la.
        global = env.mergeInto(global,
                ConfigResolver.configKeys(targetOf(projectOverrides, global, env)));

        Map<String, String> defaults = new HashMap<>();
        defaults.put(ConfigResolver.KEY_IMAGE_NAME, project.getName());
        String version = String.valueOf(project.getVersion());
        if (!version.isBlank() && !"unspecified".equals(version)) {
            defaults.put(ConfigResolver.KEY_TAG, version);
        }

        return new ConfigResolver().resolve(projectOverrides, global, defaults);
    }

    /**
     * O target, na mesma ordem que o {@link ConfigResolver} usa — projeto, global, e agora
     * tambem o ambiente. Um valor invalido NAO explode aqui: ele cai em {@code nexus} so para
     * montar a lista de chaves a procurar no ambiente, e quem reclama e o
     * {@code ConfigResolver.resolve()}, com a mensagem dele. Explodir aqui trocaria um erro
     * que diz "target invalido" por um erro de dentro do leitor de configuracao.
     */
    private static RegistryTarget targetOf(Map<String, String> project, Map<String, String> global,
                                           EnvConfigSource env) {
        String value = project.get(ConfigResolver.KEY_TARGET);
        if (value == null || value.isBlank()) {
            value = global.get(ConfigResolver.KEY_TARGET);
        }
        if (value == null || value.isBlank()) {
            value = env.get(ConfigResolver.KEY_TARGET);
        }
        try {
            // `from` devolve null quando nao ha valor -- o default `nexus` e aplicado pelo
            // ConfigResolver, e aqui ele precisa ser aplicado de novo para montar a lista.
            RegistryTarget target = RegistryTarget.from(value);
            return target == null ? RegistryTarget.NEXUS : target;
        } catch (RuntimeException e) {
            return RegistryTarget.NEXUS;
        }
    }

    private static void put(Map<String, String> map, String key, String value) {
        if (value != null && !value.isBlank()) {
            map.put(key, value.trim());
        }
    }
}
