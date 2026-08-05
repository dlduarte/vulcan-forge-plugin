package io.github.dlduarte.maven;

import io.github.dlduarte.ForgeException;
import io.github.dlduarte.config.ForgeGoal;
import io.github.dlduarte.config.VulcanForgeConfig;
import io.github.dlduarte.publish.MavenPackagePublisher;
import org.apache.maven.plugin.MojoExecutionException;
import org.apache.maven.plugins.annotations.Mojo;
import org.apache.maven.plugins.annotations.Parameter;
import org.apache.maven.project.MavenProject;

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
 *
 * <p><b>Multi-modulo:</b> o goal e um agregador — roda uma unica vez, no topo do reator, mesmo
 * que o plugin esteja declarado num parent pom herdado por N modulos. O {@code clean deploy} do
 * filho cobre o reator inteiro na ordem correta, publicando o parent e todos os modulos. Para
 * excluir um modulo, use o {@code <maven.deploy.skip>true</maven.deploy.skip>} nativo do Maven
 * nas {@code <properties>} dele.
 */
@Mojo(name = "maven-publish", aggregator = true, requiresProject = true, threadSafe = true)
public class MavenPublishMojo extends AbstractVulcanForgeMojo {

    /** Pula os testes no build de publicacao ({@code -DskipTests}). */
    @Parameter(property = "vulcanforge.skipTests", defaultValue = "false")
    private boolean skipTests;

    @Override
    public void execute() throws MojoExecutionException {
        if (skip) {
            getLog().info("vulcan-forge: maven-publish pulado (vulcanforge.skip=true).");
            return;
        }
        if (ForkedMavenBuild.isForked(session)) {
            getLog().debug("vulcan-forge: maven-publish pulado dentro do build filho.");
            return;
        }

        // Antes de qualquer coisa: os modulos que serao publicados declararam o goal?
        requireAnyModuleEnables(ForgeGoal.MAVEN);
        requireEveryDeployedModuleEnables();

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

        int modules = session.getProjects().size();
        if (modules > 1) {
            getLog().info("vulcan-forge: reator com " + modules + " modulos; um unico 'clean deploy' "
                    + "publica todos na ordem do Maven.");
        }

        runDeploy(altRepo);

        getLog().info("vulcan-forge: pacote Maven publicado.");
    }

    /**
     * O {@code clean deploy} do filho cobre o reator inteiro de uma vez, entao a garantia do
     * {@code <enabledGoals>} tem de valer antes do fork: <b>todo</b> modulo que o filho vai
     * publicar precisa ter declarado {@code maven-publish}. Modulos que desligaram o deploy
     * com {@code maven.deploy.skip} nao entram na conta — nao serao publicados.
     */
    private void requireEveryDeployedModuleEnables() throws MojoExecutionException {
        List<String> notEnabled = new ArrayList<>();
        for (MavenProject module : ReactorModules.deployable(session.getProjects())) {
            if (!enabledGoalsFor(module).isEnabled(ForgeGoal.MAVEN)) {
                notEnabled.add(module.getArtifactId());
            }
        }
        if (notEnabled.isEmpty()) {
            return;
        }
        throw new MojoExecutionException("vulcan-forge: o 'clean deploy' publicaria o reator inteiro, "
                + "mas estes modulos nao declaram '" + ForgeGoal.MAVEN.mavenGoal()
                + "' em <enabledGoals>: " + String.join(", ", notEnabled) + ".\n"
                + "Para cada um, escolha: acrescente <goal>" + ForgeGoal.MAVEN.mavenGoal()
                + "</goal> ao <vulcanforge.enabledGoals> das <properties> dele, "
                + "ou tire-o do deploy com <" + ReactorModules.DEPLOY_SKIP_PROPERTY + ">true</"
                + ReactorModules.DEPLOY_SKIP_PROPERTY + "> (nativo do Maven).");
    }

    /**
     * Roda {@code clean deploy} num Maven filho na raiz do reator, com o
     * {@code altDeploymentRepository} apontando para o destino resolvido.
     */
    private void runDeploy(String altRepo) throws MojoExecutionException {
        List<String> goals = List.of("clean", "deploy");
        getLog().info("vulcan-forge: publicando o artefato (" + String.join(" ", goals) + ")...");

        Properties props = new Properties();
        props.setProperty("altDeploymentRepository", altRepo);
        if (skipTests) {
            props.setProperty("skipTests", "true");
        }

        // Sem forcar -am: aqui o -pl do usuario define exatamente o que deve ser publicado.
        new ForkedMavenBuild(session, getLog()).run(goals, props, false, "build de publicacao");
    }
}
