namespace VulcanForge;

/// <summary>
/// Orquestra a publicacao da imagem:
/// <list type="number">
///   <item><c>docker build -f &lt;dockerfile&gt; -t &lt;imageName&gt;:&lt;tag&gt; [--label k=v ...] &lt;contexto&gt;</c></item>
///   <item><c>docker tag &lt;local&gt; &lt;ref remota&gt;</c></item>
///   <item><c>docker login &lt;registry&gt; -u &lt;user&gt; --password-stdin</c> (se ha credenciais)</item>
///   <item><c>docker push &lt;ref remota&gt;</c></item>
/// </list>
/// </summary>
/// <remarks>
/// ⚠️ <b>ESTA CLASSE NAO COMPILA O PROJETO, e isso e o desenho.</b> O lado Java deixa o
/// <c>clean</c>/<c>build</c> para a ferramenta hospedeira; aqui nao ha hospedeira, e a decisao
/// e mais forte: a imagem empacota o que ja foi publicado em disco pelo <c>dotnet publish</c>.
///
/// O motivo e a esteira que consome isto: se a imagem reconstroi, os bytes publicados nao sao
/// os que passaram pelas conferencias do job de qualidade - sao outros, do mesmo codigo-fonte.
/// E quase sempre a mesma coisa, e o <i>quase</i> e o incidente que ninguem reproduz.
/// </remarks>
public sealed class DockerImagePublisher
{
    private readonly IProcessRunner _runner;
    private readonly IForgeLogger _log;

    public DockerImagePublisher(IProcessRunner runner, IForgeLogger? log = null)
    {
        _runner = runner;
        _log = log ?? ConsoleForgeLogger.Instance;
    }

    /// <param name="cfg">configuracao resolvida</param>
    /// <param name="creds">credenciais; <c>null</c> ou incompletas pulam o login</param>
    /// <param name="projectDir">raiz do projeto (base do Dockerfile e do contexto)</param>
    /// <param name="env">ambiente, para os rotulos</param>
    public void Publish(
        VulcanForgeConfig cfg,
        Credentials? creds,
        string projectDir,
        IReadOnlyDictionary<string, string> env)
    {
        Validate(cfg, creds, projectDir);

        var localRef = $"{cfg.ImageName}:{cfg.Tag}";
        var remoteRef = BuildRemoteRef(cfg);
        var dockerfile = ResolveDockerfile(cfg, projectDir);
        var contexto = Path.GetFullPath(Path.Combine(projectDir, cfg.ContextPath));

        if (!Directory.Exists(contexto))
        {
            throw new ForgeException(
                $"contexto de build nao encontrado: {contexto}. Confira 'contextPath'.");
        }

        _log.Info($"Publicando imagem Docker: {localRef} -> {remoteRef}");

        // 1. build
        //
        // Os rotulos entram AQUI, e nao num segundo build por cima da imagem pronta: assim eles
        // fazem parte da unica imagem que existe, e nao ha janela entre publicar e carimbar.
        var buildArgs = new List<string> { "build", "-f", dockerfile, "-t", localRef };

        if (!string.IsNullOrWhiteSpace(cfg.DockerTarget))
        {
            buildArgs.Add("--target");
            buildArgs.Add(cfg.DockerTarget!);
        }

        foreach (var (chave, valor) in ImageLabels.Resolve(cfg, env, _log))
        {
            buildArgs.Add("--label");
            buildArgs.Add($"{chave}={valor}");
        }

        foreach (var (chave, valor) in cfg.BuildArgs)
        {
            buildArgs.Add("--build-arg");
            buildArgs.Add($"{chave}={valor}");
        }

        buildArgs.Add(contexto);
        _runner.Exec(buildArgs, projectDir);

        // 2. tag
        _runner.Exec(new[] { "tag", localRef, remoteRef }, projectDir);

        // 3. login (opcional)
        if (creds is not null && creds.Completa)
        {
            var registry = RegistryHost(cfg);
            var loginArgs = new List<string> { "login" };
            if (!string.IsNullOrWhiteSpace(registry)) loginArgs.Add(registry!);
            loginArgs.Add("-u");
            loginArgs.Add(creds.Usuario!);
            loginArgs.Add("--password-stdin");
            _runner.Exec(loginArgs, projectDir, creds.Senha);
        }
        else
        {
            _log.Warn("Sem credenciais completas; pulando 'docker login'. "
                      + "O push pode falhar se o registry exigir autenticacao.");
        }

        // 4. push
        _runner.Exec(new[] { "push", remoteRef }, projectDir);

        _log.Info($"Imagem publicada com sucesso: {remoteRef}");

        // 5. remocao local (opcional; falha aqui NAO invalida a publicacao)
        if (cfg.RemoveLocalImage) RemoveLocalImages(projectDir, localRef, remoteRef);
    }

    private void RemoveLocalImages(string projectDir, string localRef, string remoteRef)
    {
        try
        {
            _log.Info($"Removendo imagens locais: {localRef}, {remoteRef}");
            _runner.Exec(new[] { "rmi", "-f", localRef, remoteRef }, projectDir);
        }
        catch (ForgeException e)
        {
            _log.Warn("Nao foi possivel remover as imagens locais "
                      + $"(a publicacao FOI concluida): {e.Message}");
        }
    }

    /// <summary>Preflight: o Docker responde? Falha cedo, com mensagem que diz o que conferir.</summary>
    public void CheckDockerAvailable()
    {
        if (_runner.TryExec(new[] { "version" }) != 0)
        {
            throw new ForgeException(
                "Docker nao esta disponivel ('docker version' nao respondeu). Verifique se ele "
                + "esta instalado, no PATH e com o daemon em execucao. Num job de CI, confira "
                + "se o runner e privilegiado e se o servico dind subiu.");
        }
    }

    /// <summary>Monta <c>[registry/][namespace/]imageName:tag</c>.</summary>
    public static string BuildRemoteRef(VulcanForgeConfig cfg)
    {
        var sb = new System.Text.StringBuilder();
        var registry = RegistryHost(cfg);
        if (!string.IsNullOrWhiteSpace(registry)) sb.Append(registry).Append('/');
        if (!string.IsNullOrWhiteSpace(cfg.Namespace)) sb.Append(cfg.Namespace!.Trim('/')).Append('/');
        sb.Append(cfg.ImageName).Append(':').Append(cfg.Tag);
        return sb.ToString();
    }

    /// <summary>Host do registry, sem o esquema - que o Docker nao aceita na referencia.</summary>
    internal static string? RegistryHost(VulcanForgeConfig cfg)
    {
        var url = cfg.DockerRegistry;
        if (string.IsNullOrWhiteSpace(url)) return null;

        url = url.Trim();
        var esquema = url.IndexOf("://", StringComparison.Ordinal);
        if (esquema >= 0) url = url[(esquema + 3)..];

        var barra = url.IndexOf('/');
        if (barra >= 0) url = url[..barra];

        return url;
    }

    private static string ResolveDockerfile(VulcanForgeConfig cfg, string projectDir)
    {
        var rel = string.IsNullOrWhiteSpace(cfg.DockerfilePath) ? "Dockerfile" : cfg.DockerfilePath;
        var caminho = Path.IsPathRooted(rel) ? rel : Path.GetFullPath(Path.Combine(projectDir, rel));

        if (!File.Exists(caminho))
        {
            throw new ForgeException(
                $"Dockerfile nao encontrado em: {caminho}. Coloque um Dockerfile na raiz do "
                + "projeto ou configure 'dockerfilePath' em vulcanforge.json.");
        }
        return caminho;
    }

    /// <summary>Valida configuracao e ambiente ANTES de qualquer trabalho.</summary>
    public void Validate(VulcanForgeConfig cfg, Credentials? creds, string projectDir)
    {
        var chaveTarget = cfg.Target.ConfigKey();

        if (string.IsNullOrWhiteSpace(cfg.ImageName))
        {
            throw new ForgeException(
                "'imageName' nao configurado. O padrao e o nome do projeto (.csproj); declare-o "
                + "em vulcanforge.json ou passe --image-name.");
        }

        if (string.IsNullOrWhiteSpace(cfg.Tag))
        {
            throw new ForgeException(
                "'tag' nao configurada. O padrao e a <Version> do projeto; passe --tag "
                + "(e o que a esteira faz: --tag $IMAGE_TAG).");
        }

        if (string.IsNullOrWhiteSpace(cfg.DockerRegistry))
        {
            throw new ForgeException(
                $"Registry Docker nao configurado para o target '{chaveTarget}'. Exporte "
                + EnvConfigSource.EnvName($"{chaveTarget}.{ConfigResolver.SubDockerRegistry}") + ".");
        }

        if (!Directory.Exists(projectDir))
        {
            throw new ForgeException($"diretorio do projeto invalido: {projectDir}");
        }

        ResolveDockerfile(cfg, projectDir);

        if (creds is null || !creds.Completa)
        {
            var usuario = EnvConfigSource.EnvName($"{cfg.ServerId}.username");
            var senha = EnvConfigSource.EnvName($"{cfg.ServerId}.password");
            _log.Warn($"Credenciais ausentes/incompletas para o serverId '{cfg.ServerId}'; o push "
                      + $"sera tentado SEM login. Para autenticar, exporte {usuario} e {senha}.");
        }
    }
}
