package io.github.dlduarte.gradle;

import io.github.dlduarte.config.ConfigResolver;
import io.github.dlduarte.config.VulcanForgeConfig;
import org.gradle.api.Project;

import java.util.HashMap;
import java.util.Map;

/**
 * Monta a {@link VulcanForgeConfig} do Gradle. Toda a configuracao e global
 * (propriedades {@code vulcanforge.*}, tipicamente em {@code ~/.gradle/gradle.properties},
 * o equivalente ao {@code settings.xml} do Maven). O projeto so pode escolher o target
 * via DSL {@code vulcanForge { target = ... }}.
 */
final class GradleConfigReader {

    private GradleConfigReader() {
    }

    static VulcanForgeConfig resolve(Project project, VulcanForgeExtension ext) {
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

        Map<String, String> defaults = new HashMap<>();
        defaults.put(ConfigResolver.KEY_IMAGE_NAME, project.getName());
        String version = String.valueOf(project.getVersion());
        if (version != null && !version.isBlank() && !"unspecified".equals(version)) {
            defaults.put(ConfigResolver.KEY_TAG, version);
        }

        return new ConfigResolver().resolve(projectOverrides, global, defaults);
    }

    private static void put(Map<String, String> map, String key, String value) {
        if (value != null && !value.isBlank()) {
            map.put(key, value.trim());
        }
    }
}
