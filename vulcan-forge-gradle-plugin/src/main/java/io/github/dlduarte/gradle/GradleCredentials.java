package io.github.dlduarte.gradle;

import io.github.dlduarte.ForgeLogger;
import io.github.dlduarte.config.Credentials;
import io.github.dlduarte.config.EnvConfigSource;
import org.gradle.api.Project;

/**
 * Resolve credenciais para um {@code serverId}. Duas fontes, nesta ordem:
 *
 * <ol>
 *   <li>propriedades do Gradle ({@code gradle.properties} do projeto ou
 *       {@code ~/.gradle/gradle.properties}, ou um {@code -P} na invocacao):
 *       <pre>
 *       vulcanforge.&lt;serverId&gt;.username=USER
 *       vulcanforge.&lt;serverId&gt;.password=PASS
 *       </pre></li>
 *   <li>variaveis de ambiente (desde a 1.2.0):
 *       <pre>
 *       VULCANFORGE_&lt;SERVER_ID&gt;_USERNAME
 *       VULCANFORGE_&lt;SERVER_ID&gt;_PASSWORD
 *       </pre></li>
 * </ol>
 *
 * <p><b>A segunda fonte existe para o CI.</b> Sem ela, um pipeline so tem dois caminhos para
 * entregar a senha ao plugin: escrever um {@code gradle.properties} em disco, ou passar
 * {@code -Pvulcanforge.<serverId>.password=...} na linha de comando — onde ela aparece em
 * {@code ps} e em qualquer log que ecoe o comando. Variavel de ambiente nao tem nenhum dos
 * dois problemas.
 *
 * <p>As duas metades sao resolvidas <b>independentemente</b>: da para ter o usuario numa
 * propriedade e a senha no ambiente. E o arranjo natural de quem versiona o usuario e injeta
 * so o segredo.
 */
final class GradleCredentials {

    private GradleCredentials() {
    }

    static Credentials resolve(Project project, String serverId, ForgeLogger log) {
        return resolve(project, serverId, log, new EnvConfigSource());
    }

    /** Sobrecarga com o ambiente injetado — e o que torna esta classe testavel. */
    static Credentials resolve(Project project, String serverId, ForgeLogger log,
                               EnvConfigSource env) {
        if (serverId == null || serverId.isBlank()) {
            return null;
        }
        String user = firstNonBlank(property(project, serverId, "username"),
                env.get(serverId + ".username"));
        String pass = firstNonBlank(property(project, serverId, "password"),
                env.get(serverId + ".password"));

        if (user == null || pass == null) {
            // A mensagem cita OS DOIS caminhos porque o caso comum e a pessoa ter
            // configurado um deles e nao saber que o outro existe.
            log.warn("Credenciais nao encontradas para serverId='" + serverId + "'."
                    + " Esperado vulcanforge." + serverId + ".username/password nas propriedades"
                    + " do Gradle, ou " + EnvConfigSource.envName(serverId + ".username") + "/"
                    + EnvConfigSource.envName(serverId + ".password") + " no ambiente.");
            return null;
        }
        return new Credentials(user, pass);
    }

    private static String property(Project project, String serverId, String sufixo) {
        Object v = project.findProperty("vulcanforge." + serverId + "." + sufixo);
        return v == null ? null : v.toString();
    }

    private static String firstNonBlank(String... values) {
        for (String v : values) {
            if (v != null && !v.isBlank()) {
                return v.trim();
            }
        }
        return null;
    }
}
