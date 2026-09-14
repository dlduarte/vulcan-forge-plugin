using System.Xml.Linq;
using VulcanForge;

// =============================================================================
// vulcan-forge — o lado .NET
//
// POR QUE UMA FERRAMENTA DE LINHA DE COMANDO, e nao um plugin de build como no
// Maven e no Gradle.
//
// Nao ha, no .NET, um ciclo de vida de build com plugins de terceiros no qual
// isto se encaixe: o `dotnet publish` executa alvos do MSBuild, e prender a
// publicacao da imagem a um alvo faria a imagem ser (re)construida junto com o
// codigo -- exatamente o contrario do que a esteira que consome isto precisa.
//
// Uma ferramenta local (`dotnet tool`) tem, de graca, a propriedade que importa:
// a VERSAO fica pinada em `.config/dotnet-tools.json`, que e versionado e revisado
// -- a mesma garantia que o `<version>` do plugin no `pom.xml` da do lado Java.
// E ela e invocada como comando, que e o que um job de CI faz.
// =============================================================================

return Cli.Executar(args);

internal static class Cli
{
    internal static int Executar(string[] args)
    {
        var log = ConsoleForgeLogger.Instance;
        try
        {
            if (args.Length == 0 || args[0] is "-h" or "--help" or "help")
            {
                Ajuda();
                return args.Length == 0 ? 1 : 0;
            }

            if (args[0] is "--version")
            {
                Console.WriteLine(typeof(Cli).Assembly.GetName().Version?.ToString(3) ?? "?");
                return 0;
            }

            var goal = ForgeGoals.From(args[0]);
            var cli = ParsearArgumentos(args.Skip(1).ToArray());

            var projectDir = Path.GetFullPath(
                cli.TryGetValue("projectDir", out var d) ? d : Directory.GetCurrentDirectory());

            if (!Directory.Exists(projectDir))
            {
                throw new ForgeException($"diretorio do projeto nao existe: {projectDir}");
            }

            // A PRIMEIRA COISA QUE RODA e a checagem de goal habilitado. Nada e
            // construido, empacotado ou enviado antes dela -- e a mesma ordem do lado
            // Java, e pelo mesmo motivo: um comando digitado por engano nao pode chegar
            // a tocar o registry.
            var projeto = ProjectConfig.Load(projectDir);
            EnabledGoals.Parse(projeto.EnabledGoals).Exigir(goal);

            var env = new EnvConfigSource();
            var resolver = new ConfigResolver(env);
            var cfg = resolver.Resolve(cli, projeto, PadroesDoProjeto(projectDir));
            var creds = resolver.ResolveCredentials(cfg);

            var ambiente = AmbienteAtual();

            switch (goal)
            {
                case ForgeGoal.Docker:
                {
                    var runner = new ProcessRunner(log);
                    var publisher = new DockerImagePublisher(runner, log);
                    publisher.CheckDockerAvailable();
                    publisher.Publish(cfg, creds, projectDir, ambiente);
                    break;
                }
                case ForgeGoal.NuGet:
                {
                    var runner = new ProcessRunner(log, "dotnet");
                    var pasta = cli.TryGetValue("packageDir", out var p) ? p : "nupkg";
                    new NuGetPackagePublisher(runner, log).Publish(cfg, creds, projectDir, pasta);
                    break;
                }
            }

            return 0;
        }
        catch (ForgeException e)
        {
            // Falha ESPERADA: uma mensagem, sem stack trace. Quem le o log de um job
            // vermelho precisa da causa, e nao do caminho ate ela.
            Console.Error.WriteLine();
            Console.Error.WriteLine("ERRO: " + e.Message);
            return 1;
        }
    }

    /// <summary>
    /// Aceita <c>--chave valor</c> e <c>--chave=valor</c>, traduzindo <c>--image-name</c> para a
    /// chave <c>imageName</c> da configuracao.
    /// </summary>
    internal static Dictionary<string, string> ParsearArgumentos(string[] args)
    {
        var saida = new Dictionary<string, string>(StringComparer.Ordinal);

        for (var i = 0; i < args.Length; i++)
        {
            var a = args[i];
            if (!a.StartsWith("--", StringComparison.Ordinal))
            {
                throw new ForgeException(
                    $"argumento inesperado: '{a}'. Use --chave valor. Rode 'vulcan-forge --help'.");
            }

            string nome, valor;
            var eq = a.IndexOf('=');
            if (eq > 0)
            {
                nome = a[2..eq];
                valor = a[(eq + 1)..];
            }
            else
            {
                nome = a[2..];
                if (i + 1 >= args.Length || args[i + 1].StartsWith("--", StringComparison.Ordinal))
                {
                    // Flag sem valor: trata-se como `true`, para `--no-oci-labels` e afins
                    // poderem existir sem sintaxe propria.
                    valor = "true";
                }
                else
                {
                    valor = args[++i];
                }
            }

            saida[ParaCamelCase(nome)] = valor;
        }
        return saida;
    }

    /// <summary><c>image-name</c> -> <c>imageName</c>. E a chave que o resolver usa.</summary>
    internal static string ParaCamelCase(string kebab)
    {
        var partes = kebab.Split('-', StringSplitOptions.RemoveEmptyEntries);
        if (partes.Length == 0) return kebab;

        var sb = new System.Text.StringBuilder(partes[0].ToLowerInvariant());
        for (var i = 1; i < partes.Length; i++)
        {
            sb.Append(char.ToUpperInvariant(partes[i][0]));
            if (partes[i].Length > 1) sb.Append(partes[i][1..]);
        }
        return sb.ToString();
    }

    /// <summary>
    /// Defaults derivados do projeto: o nome do <c>.csproj</c> vira <c>imageName</c>, e a
    /// <c>&lt;Version&gt;</c> vira <c>tag</c>.
    /// </summary>
    /// <remarks>
    /// A leitura e deliberadamente rasa: o <c>.csproj</c> e o <c>Directory.Build.props</c> ao
    /// lado dele, sem seguir imports nem avaliar condicoes. E um DEFAULT -- quem precisa de
    /// exatidao passa <c>--tag</c>, que e o que a esteira faz. Tentar interpretar MSBuild aqui
    /// seria construir um segundo MSBuild pior que o primeiro.
    /// </remarks>
    internal static Dictionary<string, string> PadroesDoProjeto(string projectDir)
    {
        var padroes = new Dictionary<string, string>(StringComparer.Ordinal);

        var csproj = Directory.GetFiles(projectDir, "*.csproj").OrderBy(f => f, StringComparer.Ordinal)
            .FirstOrDefault();
        if (csproj is not null)
        {
            // Minusculo: nome de imagem Docker nao aceita maiuscula, e o nome do projeto quase
            // sempre tem (`AverbaSenigAPI`). Sem isto, o `docker build -t` falha com uma
            // mensagem sobre referencia invalida que nao menciona maiuscula.
            padroes["imageName"] = Path.GetFileNameWithoutExtension(csproj).ToLowerInvariant();
        }

        foreach (var arquivo in new[] { csproj, Path.Combine(projectDir, "Directory.Build.props") })
        {
            if (arquivo is null || !File.Exists(arquivo)) continue;
            var versao = LerVersao(arquivo);
            if (versao is not null)
            {
                padroes["tag"] = versao;
                break;
            }
        }
        return padroes;
    }

    private static string? LerVersao(string arquivo)
    {
        try
        {
            var doc = XDocument.Load(arquivo);
            // `<Version>` LITERAL: `<VersionPrefix>`, `<AssemblyVersion>` e `<FileVersion>`
            // nao sao a versao do artefato, e casar frouxo publicaria uma delas como se fosse.
            var v = doc.Descendants()
                .FirstOrDefault(e => e.Name.LocalName == "Version")?.Value?.Trim();
            return string.IsNullOrWhiteSpace(v) ? null : v;
        }
        catch (System.Xml.XmlException)
        {
            // Descritor ilegivel nao e motivo para falhar aqui: isto so alimenta um default.
            return null;
        }
    }

    private static Dictionary<string, string> AmbienteAtual()
    {
        var mapa = new Dictionary<string, string>(StringComparer.Ordinal);
        foreach (System.Collections.DictionaryEntry e in Environment.GetEnvironmentVariables())
        {
            if (e.Key is string k && e.Value is string v) mapa[k] = v;
        }
        return mapa;
    }

    private static void Ajuda()
    {
        Console.WriteLine(
            """
            vulcan-forge — publica imagem Docker e pacote NuGet de um projeto .NET.

            USO
                vulcan-forge <goal> [opcoes]

            GOALS
                docker-publish   constroi e empurra a imagem, com os rotulos da OCI
                nuget-publish    empurra os .nupkg ja empacotados

            ⚠️ O PROJETO DECLARA O QUE PODE PUBLICAR, em vulcanforge.json:

                { "enabledGoals": ["docker-publish"] }

            Um goal fora dessa lista se recusa a rodar. E a PRIMEIRA coisa conferida.

            OPCOES (vencem o vulcanforge.json, que vence o ambiente)
                --tag <v>              a tag da imagem. Padrao: a <Version> do projeto
                --image-name <n>       padrao: o nome do .csproj, em minusculas
                --namespace <n>        prefixo no registry
                --dockerfile-path <p>  padrao: Dockerfile
                --context-path <p>     contexto do build. Padrao: .
                --docker-target <t>    o estagio a construir (--target do docker)
                --build-args <k=v,..>  --build-arg do docker
                --labels <k=v,...>     rotulos extras; VENCEM os automaticos
                --oci-labels false     desliga os rotulos da OCI (decisao explicita)
                --remove-local-image false
                --project-dir <p>      padrao: o diretorio atual
                --package-dir <p>      (nuget-publish) padrao: nupkg

            AMBIENTE (coordenadas e credenciais NAO ficam no projeto)
                VULCANFORGE_NEXUS_DOCKER_REGISTRY   host do registry
                VULCANFORGE_NEXUS_USERNAME          usuario
                VULCANFORGE_NEXUS_PASSWORD          senha (entra por --password-stdin)
                VULCANFORGE_NEXUS_NUGET_URL         feed NuGet
                VULCANFORGE_TARGET                  nexus (padrao) ou github

            O COMMIT DA IMAGEM (org.opencontainers.image.revision) e descoberto do CI.
            Fora dele, exporte VULCANFORGE_REVISION=$(git rev-parse HEAD).

            ⚠️ ESTA FERRAMENTA NAO COMPILA NADA. Rode `dotnet publish` (ou `dotnet pack`)
            antes: a imagem empacota o que ja esta em disco, para que os bytes publicados
            sejam os mesmos que passaram pelas conferencias do build.
            """);
    }
}
