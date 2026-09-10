namespace VulcanForge;

/// <summary>
/// Resolve a <see cref="VulcanForgeConfig"/> a partir das tres fontes.
/// </summary>
/// <remarks>
/// <para><b>O modelo, igual ao do lado Java:</b> as <b>coordenadas do servidor</b>
/// (dockerRegistry, nugetUrl, serverId) e as credenciais sao <b>globais</b> e organizadas por
/// <b>target</b>; o projeto sobrescreve apenas a <b>identidade</b> do artefato (target,
/// namespace, imageName, tag, dockerfilePath...).</para>
///
/// <para><b>A precedencia, do mais forte para o mais fraco:</b></para>
/// <list type="number">
///   <item>a <b>linha de comando</b> - o mais explicito que existe, e o que a esteira usa para
///     a tag (<c>--tag $IMAGE_TAG</c>);</item>
///   <item><b><c>vulcanforge.json</c></b> - a declaracao versionada do projeto;</item>
///   <item>o <b>ambiente</b> (<c>VULCANFORGE_*</c>) - o que o job exporta.</item>
/// </list>
///
/// <para><b>Por que o ambiente vem por ultimo</b>, e nao primeiro: e a mesma razao do lado
/// Java. A configuracao escrita e explicita - alguem a versionou ou a digitou naquela
/// invocacao; o ambiente muda sem ninguem editar nada. Na ordem inversa, uma variavel exportada
/// num shell esquecido passaria a mandar no build de quem configurou o oposto, e nada apareceria
/// no log dizendo isso.</para>
///
/// <para>A excecao natural sao as coordenadas e credenciais, que o projeto <b>nao declara</b>:
/// para elas o ambiente e a unica fonte, entao nao ha disputa.</para>
/// </remarks>
public sealed class ConfigResolver
{
    public const string KeyTarget = "target";
    public const string KeyNamespace = "namespace";
    public const string KeyImageName = "imageName";
    public const string KeyTag = "tag";
    public const string KeyDockerfilePath = "dockerfilePath";
    public const string KeyContextPath = "contextPath";
    public const string KeyDockerTarget = "dockerTarget";
    public const string KeyRemoveLocalImage = "removeLocalImage";
    public const string KeyLabels = "labels";
    public const string KeyOciLabels = "ociLabels";
    public const string KeyBuildArgs = "buildArgs";

    // Subchaves por target: vulcanforge.<target>.<sub>
    public const string SubDockerRegistry = "dockerRegistry";
    public const string SubNuGetUrl = "nugetUrl";
    public const string SubNamespace = "namespace";
    public const string SubServerId = "serverId";

    private readonly EnvConfigSource _env;

    public ConfigResolver(EnvConfigSource env) => _env = env;

    /// <param name="cli">o que veio da linha de comando (chaves sem o prefixo)</param>
    /// <param name="projeto">o <c>vulcanforge.json</c> do projeto</param>
    /// <param name="padroes">defaults derivados do projeto (imageName do .csproj, tag da Version)</param>
    public VulcanForgeConfig Resolve(
        IReadOnlyDictionary<string, string> cli,
        ProjectConfig projeto,
        IReadOnlyDictionary<string, string> padroes)
    {
        string? Escolher(string chave, string? doProjeto)
        {
            if (cli.TryGetValue(chave, out var v) && !string.IsNullOrWhiteSpace(v)) return v.Trim();
            if (!string.IsNullOrWhiteSpace(doProjeto)) return doProjeto!.Trim();
            var doAmbiente = _env.Get(chave);
            if (doAmbiente is not null) return doAmbiente;
            return padroes.TryGetValue(chave, out var p) && !string.IsNullOrWhiteSpace(p)
                ? p.Trim()
                : null;
        }

        bool EscolherBool(string chave, bool? doProjeto, bool padrao)
        {
            var bruto = cli.TryGetValue(chave, out var v) && !string.IsNullOrWhiteSpace(v)
                ? v
                : doProjeto?.ToString() ?? _env.Get(chave);
            if (string.IsNullOrWhiteSpace(bruto)) return padrao;
            return bruto.Trim().ToLowerInvariant() switch
            {
                "true" or "1" or "yes" or "sim" => true,
                "false" or "0" or "no" or "nao" => false,
                _ => throw new ForgeException(
                    $"valor invalido para '{chave}': '{bruto}'. Use true ou false."),
            };
        }

        var target = RegistryTargets.From(Escolher(KeyTarget, projeto.Target)) ?? RegistryTarget.Nexus;
        var chaveTarget = target.ConfigKey();

        // Coordenadas: SO do ambiente. O projeto nao as declara, de proposito.
        var dockerRegistry = _env.Get($"{chaveTarget}.{SubDockerRegistry}")
                             ?? target.DefaultDockerRegistry();
        var nugetUrl = _env.Get($"{chaveTarget}.{SubNuGetUrl}") ?? target.DefaultNuGetUrl();
        var serverId = _env.Get($"{chaveTarget}.{SubServerId}") ?? chaveTarget;

        // O namespace pode vir do projeto (identidade) ou do global por target.
        var ns = Escolher(KeyNamespace, projeto.Namespace) ?? _env.Get($"{chaveTarget}.{SubNamespace}");

        // Rotulos: a forma de MAPA (vulcanforge.json) vence a de string (CLI/ambiente),
        // porque e a configuracao mais especifica que existe.
        var labels = new List<KeyValuePair<string, string>>();
        var comoTexto = cli.TryGetValue(KeyLabels, out var l) ? l : _env.Get(KeyLabels);
        labels.AddRange(ImageLabels.ParseLabels(comoTexto));
        if (projeto.Labels is not null)
        {
            foreach (var (k, v) in projeto.Labels)
            {
                labels.RemoveAll(p => p.Key == k);
                labels.Add(new(k, v));
            }
        }

        var buildArgs = ImageLabels.ParseLabels(
            cli.TryGetValue(KeyBuildArgs, out var b) ? b : _env.Get(KeyBuildArgs));

        return new VulcanForgeConfig
        {
            Target = target,
            DockerRegistry = dockerRegistry,
            NuGetUrl = nugetUrl,
            Namespace = ns,
            ImageName = Escolher(KeyImageName, projeto.ImageName),
            Tag = Escolher(KeyTag, projeto.Tag),
            DockerfilePath = Escolher(KeyDockerfilePath, projeto.DockerfilePath) ?? "Dockerfile",
            ContextPath = Escolher(KeyContextPath, projeto.ContextPath) ?? ".",
            DockerTarget = Escolher(KeyDockerTarget, projeto.DockerTarget),
            ServerId = serverId,
            RemoveLocalImage = EscolherBool(KeyRemoveLocalImage, projeto.RemoveLocalImage, true),
            OciLabels = EscolherBool(KeyOciLabels, projeto.OciLabels, true),
            Labels = labels,
            BuildArgs = buildArgs,
        };
    }

    /// <summary>
    /// As credenciais do <c>serverId</c>, do ambiente:
    /// <c>VULCANFORGE_&lt;SERVER_ID&gt;_USERNAME</c> / <c>_PASSWORD</c>.
    /// </summary>
    /// <remarks>
    /// ⚠️ SO do ambiente, e nunca de arquivo nem de argumento de linha de comando. Um
    /// <c>--password</c> funcionaria, e poria a senha em <c>ps</c> e em qualquer log que ecoe o
    /// comando - e um arquivo a poria em disco. E a mesma regra do lado Java.
    /// </remarks>
    public Credentials ResolveCredentials(VulcanForgeConfig cfg)
    {
        var id = cfg.ServerId ?? cfg.Target.ConfigKey();
        return new Credentials(_env.Get($"{id}.username"), _env.Get($"{id}.password"));
    }
}
