package io.github.dlduarte.maven;

import io.github.dlduarte.ForgeException;
import io.github.dlduarte.config.VulcanForgeConfig;
import io.github.dlduarte.publish.MavenPackagePublisher;
import org.apache.maven.execution.MavenSession;
import org.apache.maven.plugin.BuildPluginManager;
import org.apache.maven.plugin.MojoExecutionException;
import org.apache.maven.plugins.annotations.Component;
import org.apache.maven.plugins.annotations.Mojo;
import org.apache.maven.plugins.annotations.Parameter;

import static org.twdata.maven.mojoexecutor.MojoExecutor.artifactId;
import static org.twdata.maven.mojoexecutor.MojoExecutor.configuration;
import static org.twdata.maven.mojoexecutor.MojoExecutor.element;
import static org.twdata.maven.mojoexecutor.MojoExecutor.executeMojo;
import static org.twdata.maven.mojoexecutor.MojoExecutor.executionEnvironment;
import static org.twdata.maven.mojoexecutor.MojoExecutor.goal;
import static org.twdata.maven.mojoexecutor.MojoExecutor.groupId;
import static org.twdata.maven.mojoexecutor.MojoExecutor.name;
import static org.twdata.maven.mojoexecutor.MojoExecutor.plugin;
import static org.twdata.maven.mojoexecutor.MojoExecutor.version;

/**
 * Publica o pacote Maven do projeto (jar/pom) no repositorio configurado, reusando
 * o {@code maven-deploy-plugin} nativo. Resolve a URL de destino a partir da config
 * do Vulcan Forge e injeta como {@code altDeploymentRepository}, cujo id ({@code serverId})
 * deve casar com um {@code <server>} do settings.xml (para as credenciais).
 */
@Mojo(name = "maven-publish", requiresProject = true, threadSafe = true)
public class MavenPublishMojo extends AbstractVulcanForgeMojo {

    /** Versao do maven-deploy-plugin usada na delegacao. */
    @Parameter(property = "vulcanforge.deployPluginVersion", defaultValue = "3.1.1")
    private String deployPluginVersion;

    @Parameter(defaultValue = "${session}", readonly = true, required = true)
    private MavenSession session;

    @Component
    private BuildPluginManager pluginManager;

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

        executeMojo(
                plugin(
                        groupId("org.apache.maven.plugins"),
                        artifactId("maven-deploy-plugin"),
                        version(deployPluginVersion)),
                goal("deploy"),
                configuration(
                        element(name("altDeploymentRepository"), altRepo)),
                executionEnvironment(project, session, pluginManager));
    }
}
