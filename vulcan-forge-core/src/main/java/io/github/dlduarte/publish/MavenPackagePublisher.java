package io.github.dlduarte.publish;

import io.github.dlduarte.ForgeException;
import io.github.dlduarte.config.VulcanForgeConfig;

/**
 * Auxiliar para a publicacao de pacote Maven. Nao faz upload: apenas fornece a URL do
 * repositorio de destino (ja resolvida por target no {@link io.github.dlduarte.config.ConfigResolver}),
 * para que o Mojo/Task delegue ao deploy nativo ({@code maven-deploy-plugin} / {@code maven-publish}).
 */
public final class MavenPackagePublisher {

    private MavenPackagePublisher() {
    }

    /** Retorna a URL do repositorio Maven de destino, ou lanca se nao configurada. */
    public static String resolveRepositoryUrl(VulcanForgeConfig cfg) {
        String url = cfg.getMavenUrl();
        if (url == null || url.isBlank()) {
            throw new ForgeException("URL do repositorio Maven nao configurada para o target "
                    + cfg.getTarget() + " (defina vulcanforge." + cfg.getTarget().configKey()
                    + ".mavenUrl no settings.xml/gradle.properties).");
        }
        return url.trim();
    }
}
