package io.github.dlduarte.gradle;

import io.github.dlduarte.ForgeLogger;
import io.github.dlduarte.config.VulcanForgeConfig;
import io.github.dlduarte.publish.MavenPackagePublisher;
import org.gradle.api.DefaultTask;
import org.gradle.api.Project;
import org.gradle.api.tasks.TaskAction;

/**
 * Task {@code vulcanMavenPublish}: publica o pacote Maven reusando o plugin
 * {@code maven-publish} do Gradle. O repositorio de destino (URL + credenciais) e
 * configurado por {@link VulcanForgePlugin} a partir da config do Vulcan Forge; esta
 * task depende de {@code publish} e apenas registra o destino resolvido.
 */
public class MavenPublishTask extends DefaultTask {

    @TaskAction
    public void run() {
        Project project = getProject();
        VulcanForgeExtension ext = project.getExtensions().getByType(VulcanForgeExtension.class);

        VulcanForgeConfig cfg = GradleConfigReader.resolve(project, ext);
        ForgeLogger log = new GradleForgeLogger(getLogger());

        String url = MavenPackagePublisher.resolveRepositoryUrl(cfg);
        log.info("vulcan-forge: pacote Maven publicado no repositorio '" + url + "'.");
        if (project.getPluginManager().findPlugin("maven-publish") == null) {
            log.warn("O plugin 'maven-publish' nao esta aplicado; nenhuma publicacao foi executada. "
                    + "Aplique 'maven-publish' e defina as publicacoes.");
        }
    }
}
