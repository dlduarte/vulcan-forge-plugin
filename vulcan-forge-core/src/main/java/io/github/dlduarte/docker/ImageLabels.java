package io.github.dlduarte.docker;

import io.github.dlduarte.ForgeLogger;
import io.github.dlduarte.config.VulcanForgeConfig;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Monta os rotulos (<i>labels</i>) que vao para o {@code docker build}.
 *
 * <h2>Por que isto existe</h2>
 *
 * <p>Uma imagem publicada sem rotulo nao sabe dizer <b>de qual commit ela saiu</b>. Enquanto a
 * imagem esta no pipeline que a construiu, a resposta esta no ambiente do job; depois que o
 * pipeline acaba, ela desaparece — e o {@code git log} nao ajuda, porque ele nao sabe qual
 * commit virou imagem.
 *
 * <p>Isso deixa de ser detalhe no momento em que uma esteira promove imagem em vez de
 * reconstrui-la: para saber de onde cortar a branch de release, ou para conferir que o codigo
 * promovido e o codigo empacotado, alguem precisa perguntar a propria imagem.
 *
 * <h2>Os rotulos automaticos, e por que sao automaticos</h2>
 *
 * <p>Sao os nomes padrao da <a href="https://github.com/opencontainers/image-spec">OCI</a>, que
 * {@code docker inspect}, Portainer, Trivy e os registries ja sabem exibir:
 *
 * <table border="1">
 *   <caption>Rotulos escritos sem configuracao</caption>
 *   <tr><th>rotulo</th><th>de onde vem</th></tr>
 *   <tr><td>{@code org.opencontainers.image.version}</td><td>a {@code tag} da configuracao</td></tr>
 *   <tr><td>{@code org.opencontainers.image.revision}</td><td>o commit, do ambiente de CI</td></tr>
 *   <tr><td>{@code org.opencontainers.image.created}</td><td>o instante do build, em UTC</td></tr>
 *   <tr><td>{@code org.opencontainers.image.source}</td><td>a URL do repositorio, do ambiente de CI</td></tr>
 * </table>
 *
 * <p><b>Eles nao sao opcionais por padrao</b>, e essa e a decisao central desta classe.
 * Configuracao que precisa ser lembrada e configuracao que sera esquecida em algum projeto —
 * e o projeto que a esquecer so descobre meses depois, quando alguem precisar da resposta que
 * a imagem nao tem. Quem nao quiser os rotulos desliga com {@code vulcanforge.ociLabels=false},
 * o que e uma decisao explicita e visivel no build.
 *
 * <h2>Quando o commit nao e descoberto</h2>
 *
 * <p>Fora de um CI conhecido, {@code revision} fica sem valor. A imagem e publicada assim
 * mesmo — o plugin tambem roda na maquina de quem desenvolve, e falhar ali seria transformar
 * um metadado em obstaculo. Mas o log <b>avisa</b>, dizendo o que foi procurado: rotulo
 * ausente e silencio e como se descobre tarde demais.
 *
 * <p>Uma esteira que dependa do rotulo deve conferir que ele chegou, e nao supor. Ver
 * {@link #revisionHelp()}.
 */
public final class ImageLabels {

    /** O commit que virou a imagem. E o rotulo do qual uma esteira de promocao depende. */
    public static final String OCI_REVISION = "org.opencontainers.image.revision";
    /** A versao da imagem — a mesma da tag. */
    public static final String OCI_VERSION = "org.opencontainers.image.version";
    /** Instante do build, ISO-8601 em UTC. */
    public static final String OCI_CREATED = "org.opencontainers.image.created";
    /** URL do repositorio de origem. */
    public static final String OCI_SOURCE = "org.opencontainers.image.source";

    /**
     * Variaveis de ambiente consultadas para descobrir o commit, na ordem.
     *
     * <p>{@code VULCANFORGE_REVISION} vem primeiro: e a saida de quem constroi fora de um CI
     * conhecido, ou de quem precisa sobrescrever o que o CI diz.
     */
    static final String[] REVISION_ENV = {
            "VULCANFORGE_REVISION",  // explicito
            "CI_COMMIT_SHA",         // GitLab CI
            "GITHUB_SHA",            // GitHub Actions
            "BUILD_VCS_NUMBER",      // TeamCity
            "GIT_COMMIT",            // Jenkins (git plugin)
    };

    private ImageLabels() {
    }

    /**
     * Os rotulos efetivos, na ordem em que serao passados ao {@code docker build}.
     *
     * <p>Os explicitos <b>vencem</b> os automaticos: quem configurou um valor a mao disse o que
     * queria, e sobrescreve-lo aqui seria ignorar configuracao explicita.
     *
     * @param cfg configuracao resolvida
     * @param env ambiente do processo (injetavel para teste)
     * @param log para o aviso de {@code revision} ausente; pode ser {@code null}
     * @return mapa ordenado, possivelmente vazio
     */
    public static Map<String, String> resolve(VulcanForgeConfig cfg,
                                              Map<String, String> env,
                                              ForgeLogger log) {
        Map<String, String> out = new LinkedHashMap<>();
        Map<String, String> ambiente = env == null ? Map.of() : env;
        ForgeLogger logger = log != null ? log : ForgeLogger.CONSOLE;

        if (cfg.isOciLabels()) {
            put(out, OCI_VERSION, cfg.getTag());
            put(out, OCI_CREATED, Instant.now().truncatedTo(ChronoUnit.SECONDS).toString());
            put(out, OCI_SOURCE, sourceUrl(ambiente));

            String revision = revision(ambiente);
            if (revision != null) {
                out.put(OCI_REVISION, revision);
            } else {
                logger.warn("Sem " + OCI_REVISION + ": nao descobri o commit desta imagem. "
                        + revisionHelp());
            }
        }

        // Explicitos por ultimo, para vencerem os automaticos.
        if (cfg.getLabels() != null) {
            for (Map.Entry<String, String> e : cfg.getLabels().entrySet()) {
                put(out, e.getKey(), e.getValue());
            }
        }
        return out;
    }

    /** Texto de ajuda, reusado no aviso e por quem precise explicar a mesma coisa. */
    public static String revisionHelp() {
        return "Procurei, nesta ordem: " + String.join(", ", REVISION_ENV)
                + ". Fora de um CI, exporte VULCANFORGE_REVISION=$(git rev-parse HEAD). "
                + "A imagem sera publicada de qualquer forma — quem depende do rotulo "
                + "deve conferir que ele chegou, e nao supor.";
    }

    /** O commit, da primeira variavel de ambiente que tiver valor. */
    static String revision(Map<String, String> env) {
        for (String nome : REVISION_ENV) {
            String v = env.get(nome);
            if (v != null && !v.isBlank()) {
                return v.trim();
            }
        }
        return null;
    }

    /**
     * A URL do repositorio de origem.
     *
     * <p>No GitHub Actions ela nao existe pronta: monta-se de {@code GITHUB_SERVER_URL} e
     * {@code GITHUB_REPOSITORY}.
     */
    static String sourceUrl(Map<String, String> env) {
        String direto = firstNonBlank(env.get("VULCANFORGE_SOURCE"), env.get("CI_PROJECT_URL"));
        if (direto != null) {
            return direto;
        }
        String server = firstNonBlank(env.get("GITHUB_SERVER_URL"), "https://github.com");
        String repo = env.get("GITHUB_REPOSITORY");
        return (repo == null || repo.isBlank()) ? null : server + "/" + repo.trim();
    }

    /**
     * Converte {@code k=v,k2=v2} num mapa ordenado.
     *
     * <p>Existe para as formas em que a configuracao e uma STRING — {@code -Dvulcanforge.labels},
     * {@code gradle.properties}, variavel de ambiente — e nao um mapa. No POM e no
     * {@code build.gradle} use a forma de mapa, que nao tem a limitacao abaixo.
     *
     * <p>Separadores: virgula ou quebra de linha. A divisao de cada par e no <b>primeiro</b>
     * {@code =}, entao o valor pode conte-lo; nao pode conter o separador.
     */
    public static Map<String, String> parse(String texto) {
        Map<String, String> out = new LinkedHashMap<>();
        if (texto == null || texto.isBlank()) {
            return out;
        }
        for (String par : texto.split("[,\\n]")) {
            String p = par.trim();
            if (p.isEmpty()) {
                continue;
            }
            int eq = p.indexOf('=');
            if (eq <= 0) {
                // Par sem `=` e quase sempre erro de digitacao. Ignorar em silencio produziria
                // uma imagem sem o rotulo que alguem acha que configurou.
                throw new io.github.dlduarte.ForgeException(
                        "Rotulo invalido em 'labels': '" + p + "'. O formato e chave=valor, "
                                + "com virgula entre pares (ex.: 'time=pagamentos,tier=api').");
            }
            put(out, p.substring(0, eq), p.substring(eq + 1));
        }
        return out;
    }

    private static void put(Map<String, String> map, String chave, String valor) {
        if (chave == null || chave.isBlank() || valor == null || valor.isBlank()) {
            return;
        }
        map.put(chave.trim(), valor.trim());
    }

    private static String firstNonBlank(String... valores) {
        for (String v : valores) {
            if (v != null && !v.isBlank()) {
                return v.trim();
            }
        }
        return null;
    }
}
