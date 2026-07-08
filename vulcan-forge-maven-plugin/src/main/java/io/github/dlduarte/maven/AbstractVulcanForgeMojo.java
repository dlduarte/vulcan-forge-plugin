package io.github.dlduarte.maven;

import io.github.dlduarte.ForgeLogger;
import io.github.dlduarte.config.ConfigResolver;
import io.github.dlduarte.config.Credentials;
import io.github.dlduarte.config.VulcanForgeConfig;
import org.apache.maven.plugin.AbstractMojo;
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

/**
 * Base dos Mojos do Vulcan Forge.
 *
 * <p>Toda a configuracao e <b>global</b>: propriedades {@code vulcanforge.*} de um
 * {@code <profile>} ativo do {@code settings.xml}, organizadas por target. A unica coisa
 * que o projeto pode sobrescrever e o <b>target</b> (qual servidor: nexus ou github).
 * Credenciais vem de um {@code <server>} do {@code settings.xml} (id = {@code serverId} do target).
 */
public abstract class AbstractVulcanForgeMojo extends AbstractMojo {

    @Parameter(defaultValue = "${project}", readonly = true, required = true)
    protected MavenProject project;

    @Parameter(defaultValue = "${settings}", readonly = true, required = true)
    protected Settings settings;

    @Component
    protected SettingsDecrypter settingsDecrypter;

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

    protected VulcanForgeConfig resolveConfig() {
        Map<String, String> projectOverrides = new HashMap<>();
        put(projectOverrides, ConfigResolver.KEY_TARGET, target);
        put(projectOverrides, ConfigResolver.KEY_NAMESPACE, namespace);
        put(projectOverrides, ConfigResolver.KEY_IMAGE_NAME, imageName);
        put(projectOverrides, ConfigResolver.KEY_TAG, tag);
        put(projectOverrides, ConfigResolver.KEY_DOCKERFILE_PATH, dockerfilePath);
        if (removeLocalImage != null) {
            projectOverrides.put(ConfigResolver.KEY_REMOVE_LOCAL_IMAGE, String.valueOf(removeLocalImage));
        }

        Map<String, String> global = readGlobalFromSettings();

        Map<String, String> defaults = new HashMap<>();
        defaults.put(ConfigResolver.KEY_IMAGE_NAME, project.getArtifactId());
        if (project.getVersion() != null && !project.getVersion().isBlank()) {
            defaults.put(ConfigResolver.KEY_TAG, project.getVersion());
        }

        return new ConfigResolver().resolve(projectOverrides, global, defaults);
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
