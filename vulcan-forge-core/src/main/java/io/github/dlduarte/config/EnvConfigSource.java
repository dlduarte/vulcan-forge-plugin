package io.github.dlduarte.config;

import java.util.Map;

/**
 * Le configuracao do Vulcan Forge a partir de <b>variaveis de ambiente</b>.
 *
 * <p><b>Por que isto existe.</b> No Maven a credencial chega pelo {@code settings.xml}, que
 * interpola {@code ${env.NEXUS_USER}} sozinho. No Gradle nao ha equivalente: o
 * {@code gradle.properties} nao interpola nada, e nomes de propriedade com ponto
 * ({@code vulcanforge.nexus.password}) nao podem ser expressos pelo mecanismo
 * {@code ORG_GRADLE_PROJECT_<nome>} do proprio Gradle, porque shell nenhum aceita ponto em
 * nome de variavel.
 *
 * <p>Sem esta classe, a unica forma de um pipeline passar a credencial ao plugin Gradle e
 * <b>escrevendo-a em disco</b> (um {@code gradle.properties} montado pelo job) ou
 * <b>passando-a na linha de comando</b> ({@code -Pvulcanforge.nexus.password=...}), onde ela
 * aparece em {@code ps} e em qualquer log que ecoe o comando. As duas sao piores do que uma
 * variavel de ambiente.
 *
 * <h2>A regra de nome</h2>
 * Prefixo {@code VULCANFORGE_}, ponto vira {@code _}, e camelCase vira {@code SNAKE_CASE}:
 *
 * <pre>
 * vulcanforge.target                    VULCANFORGE_TARGET
 * vulcanforge.dockerfilePath            VULCANFORGE_DOCKERFILE_PATH
 * vulcanforge.nexus.dockerRegistry      VULCANFORGE_NEXUS_DOCKER_REGISTRY
 * vulcanforge.nexus.serverId            VULCANFORGE_NEXUS_SERVER_ID
 * vulcanforge.nexus.username            VULCANFORGE_NEXUS_USERNAME
 * vulcanforge.nexus.password            VULCANFORGE_NEXUS_PASSWORD
 * </pre>
 *
 * <h2>Precedencia: propriedade vence ambiente</h2>
 * A propriedade e a configuracao <b>explicita</b> — de quem escreveu o
 * {@code gradle.properties} ou passou um {@code -P} naquela invocacao. A variavel de
 * ambiente e o <b>ambiente</b>, que muda sem ninguem editar nada.
 *
 * <p>A ordem inversa teria um modo de falha ruim: uma variavel exportada num shell
 * esquecido passaria a mandar no build de quem configurou o oposto, e nada apareceria no
 * log dizendo isso.
 */
public final class EnvConfigSource {

    /** Prefixo das variaveis de ambiente. */
    public static final String ENV_PREFIX = "VULCANFORGE_";

    private final Map<String, String> env;

    /** Usa o ambiente do processo. */
    public EnvConfigSource() {
        this(System.getenv());
    }

    /** Usa um mapa qualquer — e o que torna esta classe testavel. */
    public EnvConfigSource(Map<String, String> env) {
        this.env = env == null ? Map.of() : env;
    }

    /**
     * Nome da variavel de ambiente que corresponde a uma chave de configuracao (a chave
     * <b>sem</b> o prefixo {@code vulcanforge.}).
     */
    public static String envName(String key) {
        StringBuilder sb = new StringBuilder(ENV_PREFIX);
        for (int i = 0; i < key.length(); i++) {
            char c = key.charAt(i);
            if (c == '.' || c == '-') {
                sb.append('_');
            } else if (Character.isUpperCase(c)) {
                // camelCase -> SNAKE_CASE. O `_` so entra se ja nao houver um separador
                // antes, senao `nexus.Docker` viraria `NEXUS__DOCKER`.
                if (sb.length() > ENV_PREFIX.length() && sb.charAt(sb.length() - 1) != '_') {
                    sb.append('_');
                }
                sb.append(c);
            } else {
                sb.append(Character.toUpperCase(c));
            }
        }
        return sb.toString();
    }

    /** Valor da variavel correspondente a {@code key}, ou {@code null} se ausente/vazia. */
    public String get(String key) {
        String v = env.get(envName(key));
        return (v == null || v.isBlank()) ? null : v.trim();
    }

    /**
     * Devolve {@code global} acrescido do que houver no ambiente para as chaves informadas.
     *
     * <p><b>Nao sobrescreve o que ja existe</b> — e a precedencia descrita no cabecalho da
     * classe. O mapa recebido nao e modificado.
     */
    public Map<String, String> mergeInto(Map<String, String> global, Iterable<String> keys) {
        Map<String, String> merged = new java.util.HashMap<>(global == null ? Map.of() : global);
        for (String key : keys) {
            if (merged.containsKey(key)) {
                continue;
            }
            String value = get(key);
            if (value != null) {
                merged.put(key, value);
            }
        }
        return merged;
    }
}
