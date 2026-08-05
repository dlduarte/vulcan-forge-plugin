package io.github.dlduarte.gradle;

import io.github.dlduarte.ForgeLogger;
import io.github.dlduarte.config.BuildTool;
import io.github.dlduarte.config.Credentials;
import io.github.dlduarte.config.EnabledGoals;
import io.github.dlduarte.config.ForgeGoal;
import io.github.dlduarte.config.VulcanForgeConfig;
import io.github.dlduarte.docker.DockerImagePublisher;
import io.github.dlduarte.publish.MavenPackagePublisher;
import org.gradle.api.Plugin;
import org.gradle.api.Project;
import org.gradle.api.Task;
import org.gradle.api.publish.PublishingExtension;
import org.gradle.api.specs.Spec;

import java.net.URI;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.Callable;
import java.util.stream.Collectors;

/**
 * Plugin Gradle {@code io.github.dlduarte.publish}. Registra a extensao {@code vulcanForge}
 * e as tasks {@code dockerPublish} e {@code vulcanMavenPublish}.
 *
 * <p>O projeto declara em {@code vulcanForge.enabledGoals} quais dessas tasks ele pode
 * rodar; as demais somem do grupo e se recusam a executar.
 *
 * <p>Se o plugin {@code maven-publish} estiver aplicado (e {@code vulcanMavenPublish}
 * habilitado), configura automaticamente um repositorio Maven de destino (URL +
 * credenciais) a partir da config do Vulcan Forge.
 *
 * <p><b>Multi-projeto:</b> aplicado no root de um build com subprojects, ele se propaga para
 * todos eles — o equivalente a declarar o plugin uma unica vez num parent pom do Maven. As
 * tasks do root viram agregadoras: dependem das homonimas <b>dos subprojects que declararam
 * aquele goal</b>, e a acao propria do root so roda se ele mesmo o declarar. Cada subproject
 * publica com o proprio nome ({@code imageName} = nome do projeto) e pode se excluir com
 * {@code vulcanForge { skip = true }}.
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
            aggregate(project, DOCKER_PUBLISH, ForgeGoal.DOCKER);
            aggregate(project, MAVEN_PUBLISH, ForgeGoal.MAVEN);
        }

        project.afterEvaluate(this::wire);
    }

    private static boolean isAggregatorRoot(Project project) {
        return project == project.getRootProject() && !project.getSubprojects().isEmpty();
    }

    /**
     * A task do root passa a depender da homonima de cada subproject que <b>declarou aquele
     * goal</b>. A lista e um {@link Callable} de proposito: no {@code apply} do root os
     * subprojects ainda nao foram avaliados, e o Gradle so resolve isso na montagem do grafo,
     * quando o {@code enabledGoals} de cada um ja existe.
     *
     * <p>Sem esse filtro, o root arrastaria para o grafo a task de um subproject que nunca
     * declarou o goal, e ela derrubaria o build — depois de os outros ja terem publicado.
     */
    private void aggregate(Project root, String taskName, ForgeGoal goal) {
        root.getTasks().named(taskName).configure(task ->
                task.dependsOn((Callable<List<Task>>) () -> root.getSubprojects().stream()
                        .filter(sub -> declares(sub, goal))
                        .map(sub -> sub.getTasks().findByName(taskName))
                        .filter(Objects::nonNull)
                        .collect(Collectors.toList())));
    }

    /** Se o projeto declarou aquele goal (e nao se excluiu com {@code skip}). */
    private static boolean declares(Project project, ForgeGoal goal) {
        VulcanForgeExtension ext = project.getExtensions().findByType(VulcanForgeExtension.class);
        if (ext == null || ext.isSkip()) {
            return false;
        }
        return EnabledGoals.parse(ext.getEnabledGoals(), BuildTool.GRADLE).isEnabled(goal);
    }

    /**
     * Se o usuario pediu <b>esta</b> task, e nao apenas o nome dela.
     *
     * <p>Num build multi-projeto, {@code ./gradlew dockerPublish} (nome solto) faz o Gradle
     * rodar a task em <b>todos</b> os projetos que a tem — inclusive uma lib que nunca
     * declarou o goal. Ali a recusa da task derrubaria o build depois de os outros
     * subprojects ja terem publicado, entao o certo e pular. Ja
     * {@code ./gradlew :commons:dockerPublish} e um pedido direto: ai a task roda e falha
     * explicando que o projeto nao declarou o goal.
     */
    private static boolean explicitlyRequested(Task task) {
        List<String> requested = task.getProject().getGradle().getStartParameter().getTaskNames();
        String path = task.getPath();
        boolean isRoot = task.getProject() == task.getProject().getRootProject();
        for (String name : requested) {
            if (name.equals(path) || (':' + name).equals(path)) {
                return true;
            }
            // Nome solto so conta como pedido direto num build de um projeto so.
            if (isRoot && name.equals(task.getName())) {
                return true;
            }
        }
        return false;
    }

    private void wire(Project project) {
        VulcanForgeExtension ext = extensionOf(project);
        EnabledGoals enabled = EnabledGoals.parse(ext.getEnabledGoals(), BuildTool.GRADLE);
        boolean aggregatorRoot = isAggregatorRoot(project);

        // Uma task nao habilitada sai do grupo (some do './gradlew tasks' e do painel da IDE)
        // e nao ganha nenhuma dependencia — se ainda assim for invocada pelo nome, ela falha
        // sem ter rodado build/publish. Enquanto nada for declarado, as duas continuam
        // visiveis: qualquer uma delas falha explicando como declarar.
        // No root de um build multi-projeto as duas ficam visiveis de qualquer jeito: e por
        // elas que se dispara a publicacao de todos os subprojects.
        if (enabled.isDeclared() && !aggregatorRoot) {
            hideIfDisabled(project, DOCKER_PUBLISH, ForgeGoal.DOCKER, enabled);
            hideIfDisabled(project, MAVEN_PUBLISH, ForgeGoal.MAVEN, enabled);
        }

        // dockerPublish faz clean + build antes (equivalente ao 'clean install' do Maven),
        // garantindo um unico jar em build/libs para o 'COPY build/libs/*.jar' do Dockerfile.
        boolean hasBuild = project.getTasks().findByName("build") != null;
        boolean hasClean = project.getTasks().findByName("clean") != null;
        if (enabled.isEnabled(ForgeGoal.DOCKER) && hasBuild) {
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
        project.getTasks().named(DOCKER_PUBLISH).configure(t -> t.onlyIf(
                publishable(project, ForgeGoal.DOCKER, task -> {
                    VulcanForgeConfig cfg = GradleConfigReader.resolve(project, extensionOf(project));
                    boolean has = DockerImagePublisher.hasDockerfile(
                            cfg.getDockerfilePath(), project.getProjectDir());
                    if (!has) {
                        project.getLogger().info("vulcan-forge: {} sem Dockerfile; imagem nao publicada.",
                                project.getPath());
                    }
                    return has;
                })));

        // Integracao com maven-publish, se aplicado (e se o projeto publicar pacote Maven —
        // caso contrario nem o repositorio de destino e registrado).
        boolean hasMavenPublish = project.getPluginManager().findPlugin("maven-publish") != null;
        if (enabled.isEnabled(ForgeGoal.MAVEN) && hasMavenPublish) {
            configureMavenPublishRepository(project);
            if (project.getTasks().findByName("publish") != null) {
                project.getTasks().named(MAVEN_PUBLISH).configure(t -> t.dependsOn("publish"));
            }
        }
        // No root agregador sem maven-publish nao ha pacote proprio para publicar; a task existe
        // so para disparar as dos subprojects.
        boolean ownPackage = hasMavenPublish || !aggregatorRoot;
        project.getTasks().named(MAVEN_PUBLISH).configure(t -> t.onlyIf(
                publishable(project, ForgeGoal.MAVEN, task -> ownPackage)));
    }

    /**
     * Combina o opt-out {@code vulcanForge { skip = true }} e a checagem de
     * {@code enabledGoals} com uma condicao especifica da task.
     *
     * <p>Quando o goal nao esta habilitado no projeto, a task <b>pula</b> — a menos que tenha
     * sido pedida diretamente, caso em que ela roda so para falhar com a mensagem completa
     * (ver {@link #explicitlyRequested}). No root de um build multi-projeto ela sempre pula:
     * ali a task e antes de tudo um agregador das dos subprojects.
     */
    private Spec<Task> publishable(Project project, ForgeGoal goal, Spec<Task> condition) {
        return task -> {
            if (extensionOf(project).isSkip()) {
                project.getLogger().lifecycle("vulcan-forge: {} pulado (vulcanForge.skip = true).",
                        project.getPath());
                return false;
            }
            if (!declares(project, goal)) {
                if (isAggregatorRoot(project) || !explicitlyRequested(task)) {
                    project.getLogger().info("vulcan-forge: {} nao declara '{}'; task pulada.",
                            project.getPath(), goal.gradleTask());
                    return false;
                }
                // Pedido direto: deixa a acao rodar para recusar com a mensagem de ajuda.
                return true;
            }
            return condition.isSatisfiedBy(task);
        };
    }

    private static VulcanForgeExtension extensionOf(Project project) {
        return project.getExtensions().getByType(VulcanForgeExtension.class);
    }

    private void hideIfDisabled(Project project, String taskName, ForgeGoal goal, EnabledGoals enabled) {
        if (!enabled.isEnabled(goal)) {
            project.getTasks().named(taskName).configure(t -> t.setGroup(null));
        }
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
