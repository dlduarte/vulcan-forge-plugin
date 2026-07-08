package io.github.dlduarte.gradle;

import io.github.dlduarte.ForgeLogger;
import io.github.dlduarte.config.Credentials;
import io.github.dlduarte.config.VulcanForgeConfig;
import io.github.dlduarte.publish.MavenPackagePublisher;
import org.gradle.api.Plugin;
import org.gradle.api.Project;
import org.gradle.api.publish.PublishingExtension;

import java.net.URI;

/**
 * Plugin Gradle {@code io.github.dlduarte.publish}. Registra a extensao {@code vulcanForge}
 * e as tasks {@code dockerPublish} e {@code vulcanMavenPublish}.
 *
 * <p>Se o plugin {@code maven-publish} estiver aplicado, configura automaticamente um
 * repositorio Maven de destino (URL + credenciais) a partir da config do Vulcan Forge.
 */
public class VulcanForgePlugin implements Plugin<Project> {

    static final String GROUP = "vulcan forge";
    static final String EXTENSION = "vulcanForge";

    @Override
    public void apply(Project project) {
        project.getExtensions().create(EXTENSION, VulcanForgeExtension.class);

        project.getTasks().register("dockerPublish", DockerPublishTask.class, task -> {
            task.setGroup(GROUP);
            task.setDescription("Constroi, tagueia e publica a imagem Docker no registry configurado.");
        });

        project.getTasks().register("vulcanMavenPublish", MavenPublishTask.class, task -> {
            task.setGroup(GROUP);
            task.setDescription("Publica o pacote Maven no repositorio configurado (via maven-publish).");
        });

        project.afterEvaluate(this::wire);
    }

    private void wire(Project project) {
        // dockerPublish faz clean + build antes (equivalente ao 'clean install' do Maven),
        // garantindo um unico jar em build/libs para o 'COPY build/libs/*.jar' do Dockerfile.
        boolean hasBuild = project.getTasks().findByName("build") != null;
        boolean hasClean = project.getTasks().findByName("clean") != null;
        if (hasBuild) {
            project.getTasks().named("dockerPublish").configure(t -> {
                t.dependsOn("build");
                if (hasClean) {
                    t.dependsOn("clean");
                }
            });
            if (hasClean) {
                // garante a ordem clean -> build quando ambos estao no grafo
                project.getTasks().named("build").configure(b -> b.mustRunAfter("clean"));
            }
        }

        // Integracao com maven-publish, se aplicado.
        if (project.getPluginManager().findPlugin("maven-publish") != null) {
            configureMavenPublishRepository(project);
            if (project.getTasks().findByName("publish") != null) {
                project.getTasks().named("vulcanMavenPublish").configure(t -> t.dependsOn("publish"));
            }
        }
    }

    private void configureMavenPublishRepository(Project project) {
        VulcanForgeExtension ext = project.getExtensions().getByType(VulcanForgeExtension.class);
        VulcanForgeConfig cfg = GradleConfigReader.resolve(project, ext);

        String url;
        try {
            url = MavenPackagePublisher.resolveRepositoryUrl(cfg);
        } catch (RuntimeException e) {
            // Sem URL resolvida ainda; o usuario pode nao querer publicar pacote Maven.
            return;
        }

        ForgeLogger log = new GradleForgeLogger(project.getLogger());
        Credentials creds = GradleCredentials.resolve(project, cfg.getServerId(), log);

        PublishingExtension publishing = project.getExtensions().getByType(PublishingExtension.class);
        publishing.getRepositories().maven(repo -> {
            repo.setName("vulcanForge");
            repo.setUrl(URI.create(url));
            if (creds != null && creds.isComplete()) {
                repo.credentials(c -> {
                    c.setUsername(creds.getUsername());
                    c.setPassword(creds.getPassword());
                });
            }
        });
    }
}
