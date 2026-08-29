package io.github.dlduarte.gradle;

import io.github.dlduarte.ForgeLogger;
import io.github.dlduarte.config.BuildTool;
import io.github.dlduarte.config.Credentials;
import io.github.dlduarte.config.ConfigResolver;
import io.github.dlduarte.config.EnabledGoals;
import io.github.dlduarte.config.EnvConfigSource;
import io.github.dlduarte.config.ForgeGoal;
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
 * <p>O projeto declara em {@code vulcanForge.enabledGoals} quais dessas tasks ele pode
 * rodar; as demais somem do grupo e se recusam a executar.
 *
 * <p>Se o plugin {@code maven-publish} estiver aplicado (e {@code vulcanMavenPublish}
 * habilitado), configura automaticamente um repositorio Maven de destino (URL +
 * credenciais) a partir da config do Vulcan Forge.
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
        VulcanForgeExtension ext = project.getExtensions().getByType(VulcanForgeExtension.class);
        EnabledGoals enabled = EnabledGoals.parse(ext.getEnabledGoals(), BuildTool.GRADLE);

        // Uma task nao habilitada sai do grupo (some do './gradlew tasks' e do painel da IDE)
        // e nao ganha nenhuma dependencia — se ainda assim for invocada pelo nome, ela falha
        // sem ter rodado build/publish. Enquanto nada for declarado, as duas continuam
        // visiveis: qualquer uma delas falha explicando como declarar.
        if (enabled.isDeclared()) {
            hideIfDisabled(project, "dockerPublish", ForgeGoal.DOCKER, enabled);
            hideIfDisabled(project, "vulcanMavenPublish", ForgeGoal.MAVEN, enabled);
        }

        // dockerPublish faz clean + build antes (equivalente ao 'clean install' do Maven),
        // garantindo um unico jar em build/libs para o 'COPY build/libs/*.jar' do Dockerfile.
        //
        // ... A MENOS QUE skipBuild ESTEJA LIGADO. Num pipeline que ja compilou e CONFERIU o
        // artefato num job anterior, reconstruir aqui publicaria uma imagem com bytes
        // diferentes dos que foram verificados. E o irmao do -Dvulcanforge.skipBuild do
        // plugin Maven.
        //
        // Antes da 1.2.0 quem precisava disso usava `-x build -x clean`. Funciona, e o
        // `-x clean` e facil de esquecer: sem ele o clean APAGA o artefato que veio do job
        // anterior, e a falha aparece como um COPY sem arquivo.
        boolean hasBuild = project.getTasks().findByName("build") != null;
        boolean hasClean = project.getTasks().findByName("clean") != null;
        if (enabled.isEnabled(ForgeGoal.DOCKER) && skipBuild(project, ext)) {
            project.getLogger().lifecycle(
                    "vulcan-forge: skipBuild ligado -- dockerPublish nao roda clean/build."
                    + " A imagem sera construida a partir do que ja esta em build/libs.");
        } else if (enabled.isEnabled(ForgeGoal.DOCKER) && hasBuild) {
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

        // Integracao com maven-publish, se aplicado (e se o projeto publicar pacote Maven —
        // caso contrario nem o repositorio de destino e registrado).
        if (enabled.isEnabled(ForgeGoal.MAVEN)
                && project.getPluginManager().findPlugin("maven-publish") != null) {
            configureMavenPublishRepository(project);
            if (project.getTasks().findByName("publish") != null) {
                project.getTasks().named("vulcanMavenPublish").configure(t -> t.dependsOn("publish"));
            }
        }
    }

    /**
     * {@code skipBuild}, na ordem DSL &gt; propriedade &gt; ambiente — a mesma precedencia do
     * resto da configuracao: o explicito vence o ambiente.
     */
    private boolean skipBuild(Project project, VulcanForgeExtension ext) {
        if (ext.getSkipBuild() != null) {
            return ext.getSkipBuild();
        }
        Object prop = project.findProperty(
                ConfigResolver.PROPERTY_PREFIX + ConfigResolver.KEY_SKIP_BUILD);
        if (prop != null && !prop.toString().isBlank()) {
            return Boolean.parseBoolean(prop.toString().trim());
        }
        String env = new EnvConfigSource().get(ConfigResolver.KEY_SKIP_BUILD);
        return env != null && Boolean.parseBoolean(env);
    }

    private void hideIfDisabled(Project project, String taskName, ForgeGoal goal, EnabledGoals enabled) {
        if (!enabled.isEnabled(goal)) {
            project.getTasks().named(taskName).configure(t -> t.setGroup(null));
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
