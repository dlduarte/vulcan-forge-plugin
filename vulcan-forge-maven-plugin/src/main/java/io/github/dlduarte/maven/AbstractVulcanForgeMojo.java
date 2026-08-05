package io.github.dlduarte.maven;

import io.github.dlduarte.ForgeException;
import io.github.dlduarte.ForgeLogger;
import io.github.dlduarte.config.BuildTool;
import io.github.dlduarte.config.ConfigResolver;
import io.github.dlduarte.config.Credentials;
import io.github.dlduarte.config.EnabledGoals;
import io.github.dlduarte.config.ForgeGoal;
import io.github.dlduarte.config.VulcanForgeConfig;
import org.apache.maven.execution.MavenSession;
import org.apache.maven.plugin.AbstractMojo;
import org.apache.maven.plugin.MojoExecutionException;
import org.apache.maven.plugins.annotations.Component;
import org.apache.maven.plugins.annotations.Parameter;
import org.apache.maven.project.MavenProject;
import org.apache.maven.settings.Profile;
import org.apache.maven.settings.Server;
import org.apache.maven.settings.Settings;
import org.apache.maven.settings.building.SettingsProblem;
import org.apache.maven.settings.crypto.DefaultSettingsDecryptionRequest;
import org.apache.maven.settings.crypto.SettingsDecrypter;
import org.apache.maven.settings.crypto.SettingsDecryptionResult;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;

/**
 * Base dos Mojos do Vulcan Forge.
 *
 * <p>Toda a configuracao e <b>global</b>: propriedades {@code vulcanforge.*} de um
 * {@code <profile>} ativo do {@code settings.xml}, organizadas por target. A unica coisa
 * que o projeto pode sobrescrever e o <b>target</b> (qual servidor: nexus ou github).
 * Credenciais vem de um {@code <server>} do {@code settings.xml} (id = {@code serverId} do target).
 *
 * <p>A excecao e o {@code <enabledGoals>}: essa lista e por-projeto e obrigatoria, e define
 * quais goals do plugin podem rodar ali (ver {@link EnabledGoals}).
 */
public abstract class AbstractVulcanForgeMojo extends AbstractMojo {

    /** Sufixo da propriedade {@code vulcanforge.enabledGoals}, lida por modulo no reator. */
    static final String KEY_ENABLED_GOALS = "enabledGoals";

    @Parameter(defaultValue = "${project}", readonly = true, required = true)
    protected MavenProject project;

    @Parameter(defaultValue = "${session}", readonly = true, required = true)
    protected MavenSession session;

    @Parameter(defaultValue = "${settings}", readonly = true, required = true)
    protected Settings settings;

    @Component
    protected SettingsDecrypter settingsDecrypter;

    /**
     * Goals do Vulcan Forge habilitados <b>neste projeto</b>. Obrigatorio: um goal fora da
     * lista se recusa a rodar, para que um projeto que so distribui o jar nao acabe
     * publicando uma imagem Docker por engano.
     *
     * <pre>
     * &lt;enabledGoals&gt;
     *   &lt;goal&gt;maven-publish&lt;/goal&gt;
     * &lt;/enabledGoals&gt;
     * </pre>
     */
    @Parameter(property = "vulcanforge.enabledGoals")
    protected List<String> enabledGoals;

    // ---- Sobrescritas de identidade por-projeto (as coordenadas do servidor sao globais) ----

    /** Qual servidor usar (nexus | github). */
    @Parameter(property = "vulcanforge.target")
    protected String target;

    /** Namespace/owner do artefato (ex.: owner do ghcr.io — varia por projeto). */
    @Parameter(property = "vulcanforge.namespace")
    protected String namespace;

    @Parameter(property = "vulcanforge.imageName")
    protected String imageName;

    @Parameter(property = "vulcanforge.tag")
    protected String tag;

    @Parameter(property = "vulcanforge.dockerfilePath")
    protected String dockerfilePath;

    /** Remove a imagem local apos o push (padrao: true). */
    @Parameter(property = "vulcanforge.removeLocalImage")
    protected Boolean removeLocalImage;

    @Parameter(property = "vulcanforge.skip", defaultValue = "false")
    protected boolean skip;

    /**
     * Fail-fast: so deixa o goal seguir se o projeto o declarou em {@code <enabledGoals>}.
     *
     * @throws MojoExecutionException se o goal nao estiver habilitado (ou se nada foi declarado)
     */
    protected void requireGoalEnabled(ForgeGoal goal) throws MojoExecutionException {
        try {
            EnabledGoals.parse(enabledGoals, BuildTool.MAVEN).require(goal);
        } catch (ForgeException e) {
            throw new MojoExecutionException(e.getMessage(), e);
        }
    }

    /**
     * Os goals habilitados <b>num modulo</b> do reator. Como os goals sao agregadores e rodam
     * uma unica vez no topo, e daqui que sai a declaracao de cada modulo — a
     * {@code <configuration>} do plugin so e lida no projeto de topo.
     *
     * <p>Precedencia: {@code -D} da linha de comando &gt; {@code <properties>} efetivas do
     * modulo &gt; {@code <configuration>} do plugin (declarada no parent). Como as
     * {@code <properties>} de um modulo ja incluem o que ele herda, declarar uma vez no parent
     * vale para todos, e um modulo restringe sobrescrevendo
     * {@code <vulcanforge.enabledGoals>} nas suas proprias {@code <properties>}.
     */
    protected EnabledGoals enabledGoalsFor(MavenProject module) {
        String declared = override(module, KEY_ENABLED_GOALS, null);
        List<String> values = declared != null
                ? ReactorModules.parseList(declared)
                : enabledGoals;
        return EnabledGoals.parse(values, BuildTool.MAVEN);
    }

    /**
     * Fail-fast do reator: exige que <b>pelo menos um</b> modulo tenha declarado o goal. Num
     * projeto de um modulo so, delega para {@link #requireGoalEnabled} — a mensagem e
     * exatamente a mesma de sempre.
     *
     * @throws MojoExecutionException se nenhum modulo do reator declarar o goal
     */
    protected void requireAnyModuleEnables(ForgeGoal goal) throws MojoExecutionException {
        List<MavenProject> reactor = session.getProjects();
        if (reactor.size() == 1) {
            requireGoalEnabled(goal);
            return;
        }
        for (MavenProject module : reactor) {
            if (enabledGoalsFor(module).isEnabled(goal)) {
                return;
            }
        }
        throw new MojoExecutionException("vulcan-forge: nenhum modulo deste reator declara o goal '"
                + goal.mavenGoal() + "' (" + goal.description() + ").\n"
                + "Declare no <configuration> do vulcan-forge-maven-plugin no parent pom, para valer "
                + "para todos os modulos:\n\n"
                + "  <enabledGoals>\n    <goal>" + goal.mavenGoal() + "</goal>\n  </enabledGoals>\n\n"
                + "Um modulo pode restringir o que herdou com "
                + "<vulcanforge.enabledGoals> nas suas <properties>.");
    }

    protected VulcanForgeConfig resolveConfig() {
        return resolveConfig(project);
    }

    /**
     * Resolve a config de um modulo especifico do reator. Os defaults dinamicos
     * ({@code imageName}, {@code tag}) vem do proprio modulo, de modo que cada servico de um
     * projeto multi-modulo ganha a sua imagem sem configuracao adicional.
     */
    protected VulcanForgeConfig resolveConfig(MavenProject module) {
        Map<String, String> projectOverrides = new HashMap<>();
        put(projectOverrides, ConfigResolver.KEY_TARGET,
                override(module, ConfigResolver.KEY_TARGET, target));
        put(projectOverrides, ConfigResolver.KEY_NAMESPACE,
                override(module, ConfigResolver.KEY_NAMESPACE, namespace));
        put(projectOverrides, ConfigResolver.KEY_IMAGE_NAME,
                override(module, ConfigResolver.KEY_IMAGE_NAME, imageName));
        put(projectOverrides, ConfigResolver.KEY_TAG,
                override(module, ConfigResolver.KEY_TAG, tag));
        put(projectOverrides, ConfigResolver.KEY_DOCKERFILE_PATH,
                override(module, ConfigResolver.KEY_DOCKERFILE_PATH, dockerfilePath));
        put(projectOverrides, ConfigResolver.KEY_REMOVE_LOCAL_IMAGE,
                override(module, ConfigResolver.KEY_REMOVE_LOCAL_IMAGE,
                        removeLocalImage == null ? null : String.valueOf(removeLocalImage)));

        Map<String, String> global = readGlobalFromSettings();

        Map<String, String> defaults = new HashMap<>();
        defaults.put(ConfigResolver.KEY_IMAGE_NAME, module.getArtifactId());
        if (module.getVersion() != null && !module.getVersion().isBlank()) {
            defaults.put(ConfigResolver.KEY_TAG, module.getVersion());
        }

        return new ConfigResolver().resolve(projectOverrides, global, defaults);
    }

    /**
     * Valor efetivo de uma chave de identidade para um modulo. Precedencia:
     * {@code -D} da linha de comando &gt; {@code <properties>} efetivas do modulo &gt;
     * parametro do mojo (a {@code <configuration>} declarada no parent pom).
     *
     * <p>As {@code <properties>} do modulo ja incluem o que ele herda do parent, entao a camada
     * do meio e "o valor do parent, a menos que este modulo o sobrescreva" — que e exatamente o
     * ponto de extensao por servico num reator.
     */
    private String override(MavenProject module, String key, String mojoParameter) {
        return override(session != null ? session.getUserProperties() : null,
                module.getProperties(), key, mojoParameter);
    }

    static String override(Properties commandLine, Properties moduleProperties,
                           String key, String mojoParameter) {
        String property = ConfigResolver.PROPERTY_PREFIX + key;

        String cli = commandLine != null ? commandLine.getProperty(property) : null;
        if (cli != null && !cli.isBlank()) {
            return cli;
        }

        String fromModule = moduleProperties != null ? moduleProperties.getProperty(property) : null;
        if (fromModule != null && !fromModule.isBlank()) {
            return fromModule;
        }

        return mojoParameter;
    }

    private static void put(Map<String, String> map, String key, String value) {
        if (value != null && !value.isBlank()) {
            map.put(key, value.trim());
        }
    }

    /** Le as propriedades {@code vulcanforge.*} dos profiles ATIVOS do settings.xml. */
    private Map<String, String> readGlobalFromSettings() {
        Map<String, String> global = new HashMap<>();
        List<String> activeIds = settings.getActiveProfiles();
        for (Profile profile : settings.getProfiles()) {
            boolean active = (activeIds != null && activeIds.contains(profile.getId()))
                    || (profile.getActivation() != null && profile.getActivation().isActiveByDefault());
            if (!active) {
                continue;
            }
            for (String name : profile.getProperties().stringPropertyNames()) {
                if (name.startsWith(ConfigResolver.PROPERTY_PREFIX)) {
                    String key = name.substring(ConfigResolver.PROPERTY_PREFIX.length());
                    String value = profile.getProperties().getProperty(name);
                    if (value != null && !value.isBlank()) {
                        global.put(key, value.trim());
                    }
                }
            }
        }
        return global;
    }

    /** Resolve as credenciais do {@code <server id="serverId">} do settings.xml (descriptografadas). */
    protected Credentials resolveCredentials(String id) {
        if (id == null || id.isBlank()) {
            return null;
        }
        Server server = settings.getServer(id);
        if (server == null) {
            getLog().warn("Nenhum <server id=\"" + id + "\"> encontrado no settings.xml.");
            return null;
        }
        SettingsDecryptionResult result =
                settingsDecrypter.decrypt(new DefaultSettingsDecryptionRequest(server));
        for (SettingsProblem problem : result.getProblems()) {
            getLog().warn("settings.xml: " + problem.getMessage());
        }
        Server decrypted = result.getServer();
        return new Credentials(decrypted.getUsername(), decrypted.getPassword());
    }

    protected ForgeLogger logger() {
        return new MojoForgeLogger(getLog());
    }
}
