package io.github.dlduarte.maven;

import io.github.dlduarte.ForgeException;
import io.github.dlduarte.config.VulcanForgeConfig;
import io.github.dlduarte.publish.MavenPackagePublisher;
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
import java.util.List;
import java.util.Properties;

/**
 * Publica o pacote Maven do projeto (jar/pom) no repositorio configurado, reusando
 * o {@code deploy} nativo do Maven. Resolve a URL de destino a partir da config do
 * Vulcan Forge e injeta como {@code altDeploymentRepository}, cujo id ({@code serverId})
 * deve casar com um {@code <server>} do settings.xml (para as credenciais).
 *
 * <p>Como e um goal <b>standalone</b> ({@code mvn vulcan-forge:maven-publish}), ele empacota
 * o projeto sozinho: roda {@code clean deploy} num <b>processo Maven filho</b> (mesmo padrao
 * do {@code docker-publish}), garantindo um {@code target/} limpo antes de publicar. O deploy
 * roda no ciclo do proprio filho, entao todos os artefatos (jar principal + anexos como
 * sources/javadoc) sao publicados nativamente, sem "adocao" de artefatos entre reatores.
 * A saida do build filho fica omitida no sucesso e e despejada em caso de erro.
 */
@Mojo(name = "maven-publish", requiresProject = true, threadSafe = true)
public class MavenPublishMojo extends AbstractVulcanForgeMojo {

    @Parameter(defaultValue = "${session}", readonly = true, required = true)
    private MavenSession session;

    /** Pula os testes no build de publicacao ({@code -DskipTests}). */
    @Parameter(property = "vulcanforge.skipTests", defaultValue = "false")
    private boolean skipTests;

    @Override
    public void execute() throws MojoExecutionException {
        if (skip) {
            getLog().info("vulcan-forge: maven-publish pulado (vulcanforge.skip=true).");
            return;
        }

        VulcanForgeConfig cfg = resolveConfig();

        String repoUrl;
        try {
            repoUrl = MavenPackagePublisher.resolveRepositoryUrl(cfg);
        } catch (ForgeException e) {
            throw new MojoExecutionException(e.getMessage(), e);
        }

        String repoId = cfg.getServerId();
        if (repoId == null || repoId.isBlank()) {
            throw new MojoExecutionException("'serverId' e obrigatorio para maven-publish "
                    + "(deve casar com um <server> do settings.xml).");
        }

        // Formato do maven-deploy-plugin 3.x: id::url
        String altRepo = repoId + "::" + repoUrl;
        getLog().info("vulcan-forge: publicando pacote Maven em " + altRepo);

        runDeploy(altRepo);

        getLog().info("vulcan-forge: pacote Maven publicado.");
    }

    /**
     * Roda {@code clean deploy} num Maven filho, com o {@code altDeploymentRepository} apontando
     * para o destino resolvido. Captura toda a saida e so a mostra em caso de falha.
     */
    private void runDeploy(String altRepo) throws MojoExecutionException {
        List<String> goals = new ArrayList<>();
        goals.add("clean");
        goals.add("deploy");
        getLog().info("vulcan-forge: publicando o artefato (" + String.join(" ", goals) + ")...");

        InvocationRequest request = new DefaultInvocationRequest();
        request.setPomFile(project.getFile());
        request.setBaseDirectory(project.getBasedir());
        request.setGoals(goals);
        request.setBatchMode(true);

        Properties props = new Properties();
        props.setProperty("altDeploymentRepository", altRepo);
        if (skipTests) {
            props.setProperty("skipTests", "true");
        }
        request.setProperties(props);

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
            throw new MojoExecutionException("Nao foi possivel executar o build de publicacao. "
                    + "Verifique se o Maven esta acessivel (maven.home/M2_HOME).", e);
        }

        if (result.getExitCode() != 0) {
            dumpBuildLog(captured);
            throw new MojoExecutionException("A publicacao do pacote Maven ('" + String.join(" ", goals)
                    + "') falhou com exit code " + result.getExitCode() + ".",
                    result.getExecutionException());
        }
    }

    private void dumpBuildLog(StringBuilder captured) {
        if (captured.length() > 0) {
            getLog().error("--- saida do build de publicacao ---");
            getLog().error(System.lineSeparator() + captured);
            getLog().error("--- fim da saida do build de publicacao ---");
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
