using System.Globalization;

namespace VulcanForge;

/// <summary>
/// Monta os rotulos que vao para o <c>docker build</c>.
/// </summary>
/// <remarks>
/// <para><b>Por que isto existe.</b> Uma imagem publicada sem rotulo nao sabe dizer <b>de qual
/// commit ela saiu</b>. Enquanto a imagem esta no pipeline que a construiu, a resposta esta no
/// ambiente do job; depois que o pipeline acaba, ela desaparece - e o <c>git log</c> nao ajuda,
/// porque ele nao sabe qual commit virou imagem.</para>
///
/// <para>Isso deixa de ser detalhe no momento em que uma esteira <b>promove</b> imagem em vez de
/// reconstrui-la: para saber de onde cortar a branch de release, ou para conferir que o codigo
/// promovido e o codigo empacotado, alguem precisa perguntar a propria imagem.</para>
///
/// <para><b>Os rotulos nao sao opcionais por padrao</b>, e essa e a decisao central desta
/// classe. Configuracao que precisa ser lembrada e configuracao que sera esquecida em algum
/// projeto - e o projeto que a esquecer so descobre meses depois, quando alguem precisar da
/// resposta que a imagem nao tem. Quem nao quiser desliga com <c>ociLabels=false</c>, que e uma
/// decisao explicita e visivel.</para>
///
/// <para><b>Quando o commit nao e descoberto:</b> <c>revision</c> fica sem valor e a imagem e
/// publicada assim mesmo - a ferramenta tambem roda na maquina de quem desenvolve, e falhar ali
/// seria transformar um metadado em obstaculo. Mas o log <b>avisa</b>, dizendo o que foi
/// procurado. Uma esteira que dependa do rotulo deve <b>conferir que ele chegou</b>, e nao supor.</para>
///
/// <para>Esta classe e a traducao fiel de <c>ImageLabels.java</c>. Os nomes dos rotulos e a
/// ORDEM das variaveis de ambiente sao os mesmos - uma esteira que le o rotulo de volta nao
/// pode depender de qual mundo construiu a imagem.</para>
/// </remarks>
public static class ImageLabels
{
    /// <summary>O commit que virou a imagem. E o rotulo do qual uma esteira de promocao depende.</summary>
    public const string OciRevision = "org.opencontainers.image.revision";

    /// <summary>A versao da imagem - a mesma da tag.</summary>
    public const string OciVersion = "org.opencontainers.image.version";

    /// <summary>Instante do build, ISO-8601 em UTC.</summary>
    public const string OciCreated = "org.opencontainers.image.created";

    /// <summary>URL do repositorio de origem.</summary>
    public const string OciSource = "org.opencontainers.image.source";

    /// <summary>
    /// Variaveis consultadas para descobrir o commit, NA ORDEM.
    /// </summary>
    /// <remarks>
    /// <c>VULCANFORGE_REVISION</c> vem primeiro: e a saida de quem constroi fora de um CI
    /// conhecido, ou de quem precisa sobrescrever o que o CI diz.
    /// </remarks>
    internal static readonly string[] RevisionEnv =
    {
        "VULCANFORGE_REVISION", // explicito
        "CI_COMMIT_SHA",        // GitLab CI
        "GITHUB_SHA",           // GitHub Actions
        "BUILD_VCS_NUMBER",     // TeamCity
        "GIT_COMMIT",           // Jenkins (git plugin)
    };

    /// <summary>
    /// Os rotulos efetivos, na ordem em que serao passados ao <c>docker build</c>.
    /// </summary>
    /// <remarks>
    /// Os explicitos <b>vencem</b> os automaticos: quem configurou um valor a mao disse o que
    /// queria, e sobrescreve-lo aqui seria ignorar configuracao explicita.
    /// </remarks>
    public static IDictionary<string, string> Resolve(
        VulcanForgeConfig cfg,
        IReadOnlyDictionary<string, string> env,
        IForgeLogger? log = null)
    {
        var saida = new Dictionary<string, string>(StringComparer.Ordinal);
        var ordem = new List<string>();
        var logger = log ?? ConsoleForgeLogger.Instance;

        void Put(string chave, string? valor)
        {
            if (string.IsNullOrWhiteSpace(chave) || string.IsNullOrWhiteSpace(valor)) return;
            var k = chave.Trim();
            if (!saida.ContainsKey(k)) ordem.Add(k);
            saida[k] = valor.Trim();
        }

        if (cfg.OciLabels)
        {
            Put(OciVersion, cfg.Tag);
            // Sem fracao de segundo, como no lado Java (`truncatedTo(SECONDS)`).
            Put(OciCreated, DateTime.UtcNow.ToString("yyyy-MM-ddTHH:mm:ssZ", CultureInfo.InvariantCulture));
            Put(OciSource, SourceUrl(env));

            var revision = Revision(env);
            if (revision is not null)
            {
                Put(OciRevision, revision);
            }
            else
            {
                logger.Warn($"Sem {OciRevision}: nao descobri o commit desta imagem. {RevisionHelp()}");
            }
        }

        // Explicitos por ultimo, para vencerem os automaticos.
        foreach (var (chave, valor) in cfg.Labels) Put(chave, valor);

        // Dictionary nao garante ordem de enumeracao, e aqui a ordem importa (ver
        // OrderedLabels): devolve-se a lista na ordem em que as chaves entraram.
        var ordenado = new List<KeyValuePair<string, string>>();
        foreach (var k in ordem) ordenado.Add(new(k, saida[k]));
        return new OrderedLabels(ordenado);
    }

    /// <summary>Texto de ajuda, reusado no aviso e por quem precise explicar a mesma coisa.</summary>
    public static string RevisionHelp() =>
        "Procurei, nesta ordem: " + string.Join(", ", RevisionEnv)
        + ". Fora de um CI, exporte VULCANFORGE_REVISION=$(git rev-parse HEAD). "
        + "A imagem sera publicada de qualquer forma - quem depende do rotulo deve conferir "
        + "que ele chegou, e nao supor.";

    /// <summary>O commit, da primeira variavel de ambiente que tiver valor.</summary>
    internal static string? Revision(IReadOnlyDictionary<string, string> env)
    {
        foreach (var nome in RevisionEnv)
        {
            if (env.TryGetValue(nome, out var v) && !string.IsNullOrWhiteSpace(v)) return v.Trim();
        }
        return null;
    }

    /// <summary>
    /// A URL do repositorio de origem. No GitHub Actions ela nao existe pronta: monta-se de
    /// <c>GITHUB_SERVER_URL</c> e <c>GITHUB_REPOSITORY</c>.
    /// </summary>
    internal static string? SourceUrl(IReadOnlyDictionary<string, string> env)
    {
        var direto = PrimeiroNaoVazio(Valor(env, "VULCANFORGE_SOURCE"), Valor(env, "CI_PROJECT_URL"));
        if (direto is not null) return direto;

        var server = PrimeiroNaoVazio(Valor(env, "GITHUB_SERVER_URL"), "https://github.com");
        var repo = Valor(env, "GITHUB_REPOSITORY");
        return string.IsNullOrWhiteSpace(repo) ? null : $"{server}/{repo.Trim()}";
    }

    /// <summary>
    /// Converte <c>k=v,k2=v2</c> numa lista ordenada de pares.
    /// </summary>
    /// <remarks>
    /// Existe para as formas em que a configuracao e uma STRING - argumento de linha de comando
    /// ou variavel de ambiente. No <c>vulcanforge.json</c> use a forma de objeto, que nao tem a
    /// limitacao abaixo.
    ///
    /// Separadores: virgula ou quebra de linha. A divisao de cada par e no <b>primeiro</b>
    /// <c>=</c>, entao o valor pode conte-lo; nao pode conter o separador.
    /// </remarks>
    public static IReadOnlyList<KeyValuePair<string, string>> ParseLabels(string? texto)
    {
        var saida = new List<KeyValuePair<string, string>>();
        if (string.IsNullOrWhiteSpace(texto)) return saida;

        foreach (var par in texto.Split(new[] { ',', '\n' }, StringSplitOptions.None))
        {
            var p = par.Trim();
            if (p.Length == 0) continue;

            var eq = p.IndexOf('=');
            if (eq <= 0)
            {
                // Par sem `=` e quase sempre erro de digitacao. Ignorar em silencio produziria
                // uma imagem sem o rotulo que alguem acha que configurou.
                throw new ForgeException(
                    $"Rotulo invalido em 'labels': '{p}'. O formato e chave=valor, com virgula "
                    + "entre pares (ex.: 'time=pagamentos,tier=api').");
            }

            var chave = p[..eq].Trim();
            var valor = p[(eq + 1)..].Trim();
            if (chave.Length > 0 && valor.Length > 0) saida.Add(new(chave, valor));
        }
        return saida;
    }

    private static string? Valor(IReadOnlyDictionary<string, string> env, string chave)
        => env.TryGetValue(chave, out var v) ? v : null;

    private static string? PrimeiroNaoVazio(params string?[] valores)
    {
        foreach (var v in valores)
        {
            if (!string.IsNullOrWhiteSpace(v)) return v.Trim();
        }
        return null;
    }
}

/// <summary>
/// Dicionario que preserva a ORDEM DE INSERCAO.
/// </summary>
/// <remarks>
/// O <c>Dictionary</c> do .NET nao garante ordem de enumeracao, e aqui a ordem importa: os
/// rotulos explicitos entram DEPOIS dos automaticos justamente para vence-los, e um teste que
/// dependesse da ordem de um <c>Dictionary</c> passaria hoje e falharia noutra versao do
/// runtime. O lado Java usa <c>LinkedHashMap</c> pelo mesmo motivo.
/// </remarks>
internal sealed class OrderedLabels : IDictionary<string, string>
{
    private readonly List<KeyValuePair<string, string>> _itens;

    internal OrderedLabels(List<KeyValuePair<string, string>> itens) => _itens = itens;

    public IEnumerator<KeyValuePair<string, string>> GetEnumerator() => _itens.GetEnumerator();
    System.Collections.IEnumerator System.Collections.IEnumerable.GetEnumerator() => GetEnumerator();

    public int Count => _itens.Count;
    public bool IsReadOnly => true;
    public ICollection<string> Keys => _itens.Select(i => i.Key).ToList();
    public ICollection<string> Values => _itens.Select(i => i.Value).ToList();

    public bool ContainsKey(string key) => _itens.Any(i => i.Key == key);
    public bool Contains(KeyValuePair<string, string> item) => _itens.Contains(item);

    public bool TryGetValue(string key, out string value)
    {
        foreach (var i in _itens)
        {
            if (i.Key == key) { value = i.Value; return true; }
        }
        value = string.Empty;
        return false;
    }

    public string this[string key]
    {
        get => TryGetValue(key, out var v) ? v : throw new KeyNotFoundException(key);
        set => throw new NotSupportedException("somente leitura");
    }

    public void CopyTo(KeyValuePair<string, string>[] array, int arrayIndex)
        => _itens.CopyTo(array, arrayIndex);

    public void Add(string key, string value) => throw new NotSupportedException("somente leitura");
    public void Add(KeyValuePair<string, string> item) => throw new NotSupportedException("somente leitura");
    public void Clear() => throw new NotSupportedException("somente leitura");
    public bool Remove(string key) => throw new NotSupportedException("somente leitura");
    public bool Remove(KeyValuePair<string, string> item) => throw new NotSupportedException("somente leitura");
}
