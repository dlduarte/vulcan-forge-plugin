package io.github.dlduarte.gradle;

import io.github.dlduarte.ForgeLogger;
import io.github.dlduarte.config.Credentials;
import io.github.dlduarte.config.VulcanForgeConfig;
import io.github.dlduarte.docker.DockerImagePublisher;
import io.github.dlduarte.publish.MavenPackagePublisher;
import org.gradle.api.Plugin;
import org.gradle.api.Project;
import org.gradle.api.Task;
import org.gradle.api.publish.PublishingExtension;
import org.gradle.api.specs.Spec;

import java.net.URI;

/**
 * Plugin Gradle {@code io.github.dlduarte.publish}. Registra a extensao {@code vulcanForge}
 * e as tasks {@code dockerPublish} e {@code vulcanMavenPublish}.
 *
 * <p>Se o plugin {@code maven-publish} estiver aplicado, configura automaticamente um
 * repositorio Maven de destino (URL + credenciais) a partir da config do Vulcan Forge.
 *
 * <p><b>Multi-projeto:</b> aplicado no root de um build com subprojects, ele se propaga para
 * todos eles — o equivalente a declarar o plugin uma unica vez num parent pom do Maven. As
 * tasks do root viram agregadoras (dependem das homonimas de cada subproject) e a acao propria
 * do root e pulada quando ele nao tem Dockerfile. Cada subproject publica com o proprio nome
 * ({@code imageName} = nome do projeto) e pode se excluir com {@code vulcanForge { skip = true }}.
 */
public class VulcanForgePlugin implements Plugin<Project> {

    static final String GROUP = "vulcan forge";
    static final String EXTENSION = "vulcanForge";
    static final String DOCKER_PUBLISH = "dockerPublish";
    static final String MAVEN_PUBLISH = "vulcanMavenPublish";

    @Override
    public void apply(Project project) {
        project.getExtensions().create(EXTENSION, VulcanForgeExtension.class);

        project.getTasks().register(DOCKER_PUBLISH, DockerPublishTask.class, task -> {
            task.setGroup(GROUP);
            task.setDescription("Constroi, tagueia e publica a imagem Docker no registry configurado.");
        });

        project.getTasks().register(MAVEN_PUBLISH, MavenPublishTask.class, task -> {
            task.setGroup(GROUP);
            task.setDescription("Publica o pacote Maven no repositorio configurado (via maven-publish).");
        });

        // Root de um build multi-projeto: propaga para os subprojects, de modo que basta aplicar
        // o plugin uma vez na raiz para todos os servicos ganharem as tasks.
        if (isAggregatorRoot(project)) {
            project.getSubprojects().forEach(sub -> sub.getPluginManager().apply(VulcanForgePlugin.class));
            aggregate(project, DOCKER_PUBLISH);
            aggregate(project, MAVEN_PUBLISH);
        }

        project.afterEvaluate(this::wire);
    }

    private static boolean isAggregatorRoot(Project project) {
        return project == project.getRootProject() && !project.getSubprojects().isEmpty();
    }

    /** A task do root passa a depender da homonima de cada subproject. */
    private void aggregate(Project root, String taskName) {
        root.getTasks().named(taskName).configure(task ->
                root.getSubprojects().forEach(sub -> task.dependsOn(sub.getTasks().named(taskName))));
    }

    private void wire(Project project) {
        // dockerPublish faz clean + build antes (equivalente ao 'clean install' do Maven),
        // garantindo um unico jar em build/libs para o 'COPY build/libs/*.jar' do Dockerfile.
        boolean hasBuild = project.getTasks().findByName("build") != null;
        boolean hasClean = project.getTasks().findByName("clean") != null;
        if (hasBuild) {
            project.getTasks().named(DOCKER_PUBLISH).configure(t -> {
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

        // Um projeto so publica imagem se tiver Dockerfile — assim o root agregador e os
        // subprojects de biblioteca ficam de fora sem configuracao nenhuma.
        project.getTasks().named(DOCKER_PUBLISH).configure(t -> t.onlyIf(publishable(project, task -> {
            VulcanForgeExtension ext = extensionOf(project);
            VulcanForgeConfig cfg = GradleConfigReader.resolve(project, ext);
            boolean has = DockerImagePublisher.hasDockerfile(cfg.getDockerfilePath(), project.getProjectDir());
            if (!has) {
                project.getLogger().info("vulcan-forge: {} sem Dockerfile; imagem nao publicada.",
                        project.getPath());
            }
            return has;
        })));

        // Integracao com maven-publish, se aplicado.
        boolean hasMavenPublish = project.getPluginManager().findPlugin("maven-publish") != null;
        if (hasMavenPublish) {
            configureMavenPublishRepository(project);
            if (project.getTasks().findByName("publish") != null) {
                project.getTasks().named(MAVEN_PUBLISH).configure(t -> t.dependsOn("publish"));
            }
        }
        // No root agregador sem maven-publish nao ha pacote proprio para publicar; a task existe
        // so para disparar as dos subprojects.
        boolean ownPackage = hasMavenPublish || !isAggregatorRoot(project);
        project.getTasks().named(MAVEN_PUBLISH)
                .configure(t -> t.onlyIf(publishable(project, task -> ownPackage)));
    }

    /** Combina o opt-out {@code vulcanForge { skip = true }} com uma condicao especifica da task. */
    private Spec<Task> publishable(Project project, Spec<Task> condition) {
        return task -> {
            if (extensionOf(project).isSkip()) {
                project.getLogger().lifecycle("vulcan-forge: {} pulado (vulcanForge.skip = true).",
                        project.getPath());
                return false;
            }
            return condition.isSatisfiedBy(task);
        };
    }

    private static VulcanForgeExtension extensionOf(Project project) {
        return project.getExtensions().getByType(VulcanForgeExtension.class);
    }

    private void configureMavenPublishRepository(Project project) {
        VulcanForgeExtension ext = extensionOf(project);
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
