package io.github.dlduarte.maven;

import io.github.dlduarte.ForgeException;
import io.github.dlduarte.ForgeLogger;
import io.github.dlduarte.config.Credentials;
import io.github.dlduarte.config.VulcanForgeConfig;
import io.github.dlduarte.docker.DockerImagePublisher;
import io.github.dlduarte.process.ProcessRunner;
import org.apache.maven.execution.MavenExecutionRequest;
import org.apache.maven.execution.MavenSession;
import org.apache.maven.plugin.MojoExecutionException;
import org.apache.maven.plugins.annotations.Mojo;
import org.apache.maven.plugins.annotations.Parameter;
import org.apache.maven.shared.invoker.DefaultInvocationRequest;
import org.apache.maven.shared.invoker.DefaultInvoker;
import org.apache.maven.shared.invoker.InvocationOutputHandler;
import org.apache.maven.shared.invoker.InvocationRequest;
import org.apache.maven.shared.invoker.InvocationResult;
import org.apache.maven.shared.invoker.Invoker;
import org.apache.maven.shared.invoker.MavenInvocationException;

import java.io.File;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
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
 */
@Mojo(name = "docker-publish", requiresProject = true, threadSafe = true)
public class DockerPublishMojo extends AbstractVulcanForgeMojo {

    @Parameter(defaultValue = "${session}", readonly = true, required = true)
    private MavenSession session;

    /** Goals do build previo do artefato. */
    @Parameter(property = "vulcanforge.buildGoals", defaultValue = "clean install")
    private String buildGoals;

    /** Pula os testes no build previo ({@code -DskipTests}). */
    @Parameter(property = "vulcanforge.skipTests", defaultValue = "false")
    private boolean skipTests;

    /** Nao roda o build previo (usa o {@code target/} atual). */
    @Parameter(property = "vulcanforge.skipBuild", defaultValue = "false")
    private boolean skipBuild;

    @Override
    public void execute() throws MojoExecutionException {
        if (skip) {
            getLog().info("vulcan-forge: docker-publish pulado (vulcanforge.skip=true).");
            return;
        }

        VulcanForgeConfig cfg = resolveConfig();
        getLog().info("vulcan-forge: " + cfg);

        Credentials creds = resolveCredentials(cfg.getServerId());
        ForgeLogger log = logger();
        DockerImagePublisher publisher = new DockerImagePublisher(new ProcessRunner(log), log);

        try {
            // Fail-fast: valida config + ambiente ANTES do build (que e caro).
            publisher.validate(cfg, creds, project.getBasedir());
            publisher.checkDockerAvailable();

            if (!skipBuild) {
                runProjectBuild();
            }

            publisher.publish(cfg, creds, project.getBasedir());
        } catch (ForgeException e) {
            throw new MojoExecutionException(e.getMessage(), e);
        }
    }

    /**
     * Roda {@code buildGoals} num Maven filho, capturando toda a saida. So imprime em caso
     * de falha (com o log do build) — no sucesso o log verboso fica omitido.
     */
    private void runProjectBuild() throws MojoExecutionException {
        List<String> goals = new ArrayList<>();
        for (String g : buildGoals.trim().split("\\s+")) {
            if (!g.isBlank()) {
                goals.add(g);
            }
        }
        getLog().info("vulcan-forge: preparando o artefato (" + String.join(" ", goals) + ")...");

        InvocationRequest request = new DefaultInvocationRequest();
        request.setPomFile(project.getFile());
        request.setBaseDirectory(project.getBasedir());
        request.setGoals(goals);
        request.setBatchMode(true);

        // Propaga o contexto da execucao atual para o build filho.
        MavenExecutionRequest parent = session.getRequest();
        if (parent.getUserSettingsFile() != null) {
            request.setUserSettingsFile(parent.getUserSettingsFile());
        }
        if (parent.getGlobalSettingsFile() != null) {
            request.setGlobalSettingsFile(parent.getGlobalSettingsFile());
        }
        if (session.getLocalRepository() != null && session.getLocalRepository().getBasedir() != null) {
            request.setLocalRepositoryDirectory(new File(session.getLocalRepository().getBasedir()));
        }
        if (parent.getActiveProfiles() != null && !parent.getActiveProfiles().isEmpty()) {
            request.setProfiles(new ArrayList<>(parent.getActiveProfiles()));
        }
        request.setOffline(parent.isOffline());
        if (skipTests) {
            Properties props = new Properties();
            props.setProperty("skipTests", "true");
            request.setProperties(props);
        }

        // Captura stdout+stderr; so mostramos em caso de erro.
        StringBuilder captured = new StringBuilder();
        InvocationOutputHandler handler = line -> captured.append(line).append(System.lineSeparator());
        request.setOutputHandler(handler);
        request.setErrorHandler(handler);

        Invoker invoker = new DefaultInvoker();
        File mavenHome = resolveMavenHome();
        if (mavenHome != null) {
            invoker.setMavenHome(mavenHome);
        }

        InvocationResult result;
        try {
            result = invoker.execute(request);
        } catch (MavenInvocationException e) {
            dumpBuildLog(captured);
            throw new MojoExecutionException("Nao foi possivel executar o build previo do projeto. "
                    + "Verifique se o Maven esta acessivel (maven.home/M2_HOME).", e);
        }

        if (result.getExitCode() != 0) {
            dumpBuildLog(captured);
            throw new MojoExecutionException("O build previo ('" + String.join(" ", goals)
                    + "') falhou com exit code " + result.getExitCode() + ".",
                    result.getExecutionException());
        }

        getLog().info("vulcan-forge: artefato pronto.");
    }

    private void dumpBuildLog(StringBuilder captured) {
        if (captured.length() > 0) {
            getLog().error("--- saida do build previo ---");
            getLog().error(System.lineSeparator() + captured);
            getLog().error("--- fim da saida do build previo ---");
        }
    }

    private File resolveMavenHome() {
        String home = System.getProperty("maven.home");
        if (home == null || home.isBlank()) {
            home = System.getenv("MAVEN_HOME");
        }
        if (home == null || home.isBlank()) {
            home = System.getenv("M2_HOME");
        }
        return (home == null || home.isBlank()) ? null : new File(home);
    }
}
