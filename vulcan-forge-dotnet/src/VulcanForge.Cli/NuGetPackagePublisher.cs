namespace VulcanForge;

/// <summary>
/// Publica o pacote NuGet do projeto: <c>dotnet nuget push</c>.
/// </summary>
/// <remarks>
/// <para>
/// E o equivalente .NET do <c>maven-publish</c> do lado Java - um projeto .NET nao distribui
/// jar. Como la, ele <b>nao empacota</b>: consome os <c>.nupkg</c> que o <c>dotnet pack</c> ja
/// produziu, pelo mesmo motivo que a imagem nao compila.
/// </para>
/// <para>
/// O executavel aqui e <c>dotnet</c>, e nao <c>docker</c> - por isso ele recebe o proprio
/// <see cref="IProcessRunner"/>.
/// </para>
/// </remarks>
public sealed class NuGetPackagePublisher
{
    private readonly IProcessRunner _runner;
    private readonly IForgeLogger _log;

    public NuGetPackagePublisher(IProcessRunner runner, IForgeLogger? log = null)
    {
        _runner = runner;
        _log = log ?? ConsoleForgeLogger.Instance;
    }

    /// <param name="cfg">configuracao resolvida</param>
    /// <param name="creds">credenciais do feed; a senha e a API key</param>
    /// <param name="projectDir">raiz do projeto</param>
    /// <param name="packageDir">onde estao os <c>.nupkg</c>, relativo ao projeto</param>
    public void Publish(VulcanForgeConfig cfg, Credentials? creds, string projectDir, string packageDir)
    {
        if (string.IsNullOrWhiteSpace(cfg.NuGetUrl))
        {
            throw new ForgeException(
                $"Feed NuGet nao configurado para o target '{cfg.Target.ConfigKey()}'. Exporte "
                + EnvConfigSource.EnvName($"{cfg.Target.ConfigKey()}.{ConfigResolver.SubNuGetUrl}") + ".");
        }

        var pasta = Path.GetFullPath(Path.Combine(projectDir, packageDir));
        if (!Directory.Exists(pasta))
        {
            throw new ForgeException(
                $"pasta de pacotes nao encontrada: {pasta}. Rode 'dotnet pack -o {packageDir}' antes.");
        }

        var pacotes = Directory.GetFiles(pasta, "*.nupkg");
        if (pacotes.Length == 0)
        {
            throw new ForgeException(
                $"nenhum .nupkg em {pasta}. Esta ferramenta NAO empacota - rode 'dotnet pack' antes.");
        }

        foreach (var pacote in pacotes.OrderBy(p => p, StringComparer.Ordinal))
        {
            var args = new List<string>
            {
                "nuget", "push", pacote,
                "--source", cfg.NuGetUrl!,
                // Sem isto, um pacote que ja existe no feed derruba o comando. Republicar a
                // mesma versao e quase sempre repeticao de job, e nao erro de quem publica.
                "--skip-duplicate",
            };

            if (creds is not null && !string.IsNullOrWhiteSpace(creds.Senha))
            {
                // ⚠️ A CHAVE VAI NA LINHA DE COMANDO, E NAO HA COMO EVITAR.
                //
                // No Docker a senha entra por `--password-stdin`, e e por isso que ela nunca
                // aparece em `ps`. O `dotnet nuget push` NAO tem equivalente: nao ha
                // `--api-key-stdin`, e ele tambem nao le a chave do ambiente. As alternativas
                // sao piores - `dotnet nuget add source --password` poe a credencial na linha
                // de comando do mesmo jeito E a grava num NuGet.config em disco.
                //
                // O que da para fazer, e esta feito: o ProcessRunner MASCARA `--api-key` no
                // log desta ferramenta. O que ele nao alcanca e o `ps` da maquina durante os
                // segundos do push, nem um log de auditoria do sistema operacional.
                //
                // Quem publica num feed sensivel deve saber disso. Nao ha correcao deste lado.
                args.Add("--api-key");
                args.Add(creds.Senha!);
            }
            else
            {
                _log.Warn("Sem credencial para o feed NuGet; o push sera tentado sem --api-key.");
            }

            _runner.Exec(args, projectDir);
        }

        _log.Info($"{pacotes.Length} pacote(s) publicado(s) em {cfg.NuGetUrl}");
    }
}
