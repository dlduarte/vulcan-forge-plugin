package io.github.dlduarte.gradle;

import io.github.dlduarte.ForgeLogger;
import io.github.dlduarte.config.Credentials;
import org.gradle.api.Project;

/**
 * Resolve credenciais a partir das propriedades do Gradle
 * ({@code gradle.properties} do projeto ou {@code ~/.gradle/gradle.properties}):
 *
 * <pre>
 * vulcanforge.&lt;serverId&gt;.username=USER
 * vulcanforge.&lt;serverId&gt;.password=PASS
 * </pre>
 */
final class GradleCredentials {

    private GradleCredentials() {
    }

    static Credentials resolve(Project project, String serverId, ForgeLogger log) {
        if (serverId == null || serverId.isBlank()) {
            return null;
        }
        String prefix = "vulcanforge." + serverId + ".";
        Object user = project.findProperty(prefix + "username");
        Object pass = project.findProperty(prefix + "password");
        if (user == null || pass == null) {
            log.warn("Credenciais nao encontradas em gradle.properties para serverId='"
                    + serverId + "' (esperado " + prefix + "username/password).");
            return null;
        }
        return new Credentials(user.toString(), pass.toString());
    }
}
