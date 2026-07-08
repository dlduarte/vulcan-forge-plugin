package io.github.dlduarte.gradle;

import io.github.dlduarte.ForgeLogger;
import io.github.dlduarte.config.Credentials;
import io.github.dlduarte.config.VulcanForgeConfig;
import io.github.dlduarte.docker.DockerImagePublisher;
import io.github.dlduarte.process.ProcessRunner;
import org.gradle.api.DefaultTask;
import org.gradle.api.Project;
import org.gradle.api.tasks.TaskAction;

/**
 * Task Gradle {@code dockerPublish}: constroi, tagueia e publica a imagem Docker.
 * Depende de {@code build} (configurado no {@link VulcanForgePlugin}), de modo que
 * {@code ./gradlew dockerPublish} faz build + publicacao.
 */
public class DockerPublishTask extends DefaultTask {

    @TaskAction
    public void run() {
        Project project = getProject();
        VulcanForgeExtension ext = project.getExtensions().getByType(VulcanForgeExtension.class);

        VulcanForgeConfig cfg = GradleConfigReader.resolve(project, ext);
        ForgeLogger log = new GradleForgeLogger(getLogger());
        log.info("vulcan-forge: " + cfg);

        Credentials creds = GradleCredentials.resolve(project, cfg.getServerId(), log);

        DockerImagePublisher publisher = new DockerImagePublisher(new ProcessRunner(log), log);
        // Fail-fast: valida config + ambiente antes das operacoes Docker.
        publisher.validate(cfg, creds, project.getProjectDir());
        publisher.checkDockerAvailable();
        publisher.publish(cfg, creds, project.getProjectDir());
    }
}
