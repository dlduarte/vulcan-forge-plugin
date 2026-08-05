package io.github.dlduarte.maven;

import io.github.dlduarte.ForgeException;
import io.github.dlduarte.ForgeLogger;
import io.github.dlduarte.config.Credentials;
import io.github.dlduarte.config.ForgeGoal;
import io.github.dlduarte.config.VulcanForgeConfig;
import io.github.dlduarte.docker.DockerImagePublisher;
import io.github.dlduarte.process.ProcessRunner;
import org.apache.maven.plugin.MojoExecutionException;
import org.apache.maven.plugins.annotations.Mojo;
import org.apache.maven.plugins.annotations.Parameter;
import org.apache.maven.project.MavenProject;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;

/**
 * Constroi, tagueia e publica a imagem Docker do projeto no registry configurado.
 *
 * <p>Independente do {@code deploy} do Maven. Antes do {@code docker build}, roda um build
 * do projeto ({@code clean install} por padrao) num <b>processo Maven filho com a saida
 * capturada</b>: em caso de sucesso, so imprime uma linha de status (o log de
 * compile/testes fica omitido); em caso de erro, despeja o log do build para diagnostico.
 * Isso garante um unico jar atualizado em {@code target/} (Dockerfiles usam
 * {@code COPY target/*.jar}).
 *
 * <p><b>Multi-modulo:</b> o goal e um agregador — roda uma unica vez, no topo do reator, mesmo
 * que o plugin esteja declarado num parent pom herdado por N modulos. Faz <b>um</b> build do
 * reator inteiro e depois publica a imagem de <b>cada modulo que tenha Dockerfile e declare
 * {@code docker-publish}</b> em {@code <enabledGoals>} (ver {@link ReactorModules}). O parent e
 * as libs internas ficam de fora automaticamente.
 */
@Mojo(name = "docker-publish", aggregator = true, requiresProject = true, threadSafe = true)
public class DockerPublishMojo extends AbstractVulcanForgeMojo {

    /** Goals do build previo do artefato. */
    @Parameter(property = "vulcanforge.buildGoals", defaultValue = "clean install")
    private String buildGoals;

    /** Pula os testes no build previo ({@code -DskipTests}). */
    @Parameter(property = "vulcanforge.skipTests", defaultValue = "false")
    private boolean skipTests;

    /** Nao roda o build previo (usa o {@code target/} atual). */
    @Parameter(property = "vulcanforge.skipBuild", defaultValue = "false")
    private boolean skipBuild;

    /**
     * Restringe a publicacao a estes modulos do reator (artifactIds separados por virgula).
     * Vazio = todos os modulos com Dockerfile. Alternativa ao {@code -pl} da linha de comando.
     */
    @Parameter(property = "vulcanforge.modules")
    private String modules;

    @Override
    public void execute() throws MojoExecutionException {
        if (skip) {
            getLog().info("vulcan-forge: docker-publish pulado (vulcanforge.skip=true).");
            return;
        }
        if (ForkedMavenBuild.isForked(session)) {
            getLog().debug("vulcan-forge: docker-publish pulado dentro do build filho.");
            return;
        }

        // Antes de qualquer coisa: algum modulo do reator pode publicar imagem Docker?
        requireAnyModuleEnables(ForgeGoal.DOCKER);

        ForgeLogger log = logger();
        DockerImagePublisher publisher = new DockerImagePublisher(new ProcessRunner(log), log);

        Map<MavenProject, VulcanForgeConfig> configs;
        Map<MavenProject, Credentials> credentials = new LinkedHashMap<>();
        try {
            List<MavenProject> selected = ReactorModules.selectForDocker(
                    session.getProjects(),
                    module -> resolveConfig(module).getDockerfilePath(),
                    this::enabledGoalsFor,
                    ReactorModules.parseList(modules));

            configs = new LinkedHashMap<>();
            for (MavenProject module : selected) {
                VulcanForgeConfig cfg = resolveConfig(module);
                configs.put(module, cfg);
                credentials.put(module, resolveCredentials(cfg.getServerId()));
            }
            checkDistinctImages(configs);
            logSelection(configs);

            // Fail-fast: valida config + ambiente de TODOS os modulos ANTES do build (que e caro).
            for (Map.Entry<MavenProject, VulcanForgeConfig> entry : configs.entrySet()) {
                MavenProject module = entry.getKey();
                publisher.validate(entry.getValue(), credentials.get(module), module.getBasedir());
            }
            publisher.checkDockerAvailable();
        } catch (ForgeException e) {
            throw new MojoExecutionException(e.getMessage(), e);
        }

        if (!skipBuild) {
            runReactorBuild();
        }

        try {
            int index = 0;
            int total = configs.size();
            for (Map.Entry<MavenProject, VulcanForgeConfig> entry : configs.entrySet()) {
                MavenProject module = entry.getKey();
                index++;
                if (total > 1) {
                    getLog().info("vulcan-forge: [" + index + "/" + total + "] " + module.getArtifactId());
                }
                publisher.publish(entry.getValue(), credentials.get(module), module.getBasedir());
            }
        } catch (ForgeException e) {
            throw new MojoExecutionException(e.getMessage(), e);
        }
    }

    private void logSelection(Map<MavenProject, VulcanForgeConfig> configs) {
        if (configs.size() == 1) {
            getLog().info("vulcan-forge: " + configs.values().iterator().next());
            return;
        }
        getLog().info("vulcan-forge: " + configs.size() + " modulos do reator com imagem Docker:");
        for (Map.Entry<MavenProject, VulcanForgeConfig> entry : configs.entrySet()) {
            getLog().info("vulcan-forge:   " + entry.getKey().getArtifactId() + " -> "
                    + DockerImagePublisher.buildRemoteRef(entry.getValue()));
        }
    }

    /**
     * Dois modulos publicando na mesma referencia remota significa que um sobrescreveria o outro
     * — tipicamente por um {@code imageName}/{@code tag} fixo herdado do parent ou vindo do
     * settings.xml, que num reator se aplicaria a todos os modulos.
     */
    private void checkDistinctImages(Map<MavenProject, VulcanForgeConfig> configs) {
        Map<String, String> byRef = new LinkedHashMap<>();
        for (Map.Entry<MavenProject, VulcanForgeConfig> entry : configs.entrySet()) {
            String ref = DockerImagePublisher.buildRemoteRef(entry.getValue());
            String owner = byRef.put(ref, entry.getKey().getArtifactId());
            if (owner != null) {
                throw new ForgeException("Os modulos '" + owner + "' e '" + entry.getKey().getArtifactId()
                        + "' publicariam na mesma imagem (" + ref + "), um sobrescrevendo o outro. "
                        + "Remova o 'imageName'/'tag' fixo da configuracao global ou do parent pom "
                        + "(sem ele cada modulo usa o proprio artifactId/version), ou defina "
                        + "'vulcanforge.imageName' nas <properties> de cada modulo.");
            }
        }
    }

    /**
     * Roda {@code buildGoals} num Maven filho, na raiz do reator. Um unico build cobre todos os
     * modulos, com o Maven resolvendo a ordem e as dependencias entre eles.
     */
    private void runReactorBuild() throws MojoExecutionException {
        List<String> goals = new ArrayList<>();
        for (String g : buildGoals.trim().split("\\s+")) {
            if (!g.isBlank()) {
                goals.add(g);
            }
        }
        getLog().info("vulcan-forge: preparando o artefato (" + String.join(" ", goals) + ")...");

        Properties props = new Properties();
        if (skipTests) {
            props.setProperty("skipTests", "true");
        }

        // alsoMakeUpstream: com -pl, o filho ainda precisa compilar as dependencias irmas.
        new ForkedMavenBuild(session, getLog()).run(goals, props, true, "build previo");

        getLog().info("vulcan-forge: artefato pronto.");
    }
}
