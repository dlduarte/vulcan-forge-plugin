using System.Text;

namespace VulcanForge;

/// <summary>
/// Servidor de destino da publicacao. Espelha o <c>RegistryTarget</c> do lado Java, com os
/// mesmos nomes e os mesmos apelidos - a mesma declaracao serve aos tres mundos.
/// </summary>
public enum RegistryTarget
{
    Nexus,
    GitHubPackages,
}

public static class RegistryTargets
{
    /// <summary>Prefixo nas chaves globais: <c>vulcanforge.&lt;chave&gt;.dockerRegistry</c>.</summary>
    public static string ConfigKey(this RegistryTarget alvo) =>
        alvo == RegistryTarget.GitHubPackages ? "github" : "nexus";

    /// <summary>
    /// Registry padrao do target. O <c>docker.pkg.github.com</c> antigo esta deprecado; o
    /// GitHub Packages usa <c>ghcr.io</c>.
    /// </summary>
    public static string? DefaultDockerRegistry(this RegistryTarget alvo) =>
        alvo == RegistryTarget.GitHubPackages ? "ghcr.io" : null;

    /// <summary>Feed NuGet padrao do target.</summary>
    public static string? DefaultNuGetUrl(this RegistryTarget alvo) =>
        alvo == RegistryTarget.GitHubPackages ? "https://nuget.pkg.github.com" : null;

    public static RegistryTarget? From(string? valor)
    {
        if (string.IsNullOrWhiteSpace(valor)) return null;
        return valor.Trim().ToLowerInvariant() switch
        {
            "nexus" => RegistryTarget.Nexus,
            "github" or "github_packages" or "github-packages" or "ghp" or "ghcr"
                => RegistryTarget.GitHubPackages,
            _ => throw new ForgeException(
                $"target invalido: '{valor}'. Valores aceitos: nexus, github "
                + "(apelidos: ghp, github-packages, ghcr)."),
        };
    }
}

/// <summary>
/// Publicacao suportada. O lado Java tem <c>docker</c> e <c>maven</c>; aqui o segundo e
/// <c>nuget</c>, que e o equivalente real - um projeto .NET nao distribui jar.
/// </summary>
public enum ForgeGoal
{
    Docker,
    NuGet,
}

public static class ForgeGoals
{
    /// <summary>Nome do comando, ex.: <c>docker-publish</c>.</summary>
    public static string CommandName(this ForgeGoal goal) =>
        goal == ForgeGoal.NuGet ? "nuget-publish" : "docker-publish";

    public static string Description(this ForgeGoal goal) =>
        goal == ForgeGoal.NuGet ? "publica o pacote NuGet" : "publica a imagem Docker";

    /// <summary>
    /// Resolve um valor declarado pelo projeto.
    /// </summary>
    /// <remarks>
    /// Aceita o nome do comando, o apelido curto e as formas do lado Java, sem diferenciar
    /// maiusculas, hifens e underscores. <c>dockerPublish</c> (a task do Gradle) e aceito de
    /// proposito: quem ja tem a declaracao num projeto Java nao precisa reescreve-la para o
    /// .NET.
    /// </remarks>
    public static ForgeGoal From(string? valor)
    {
        var normalizado = (valor ?? string.Empty).Trim().ToLowerInvariant()
            .Replace("-", string.Empty).Replace("_", string.Empty);

        return normalizado switch
        {
            "docker" or "dockerpublish" => ForgeGoal.Docker,
            "nuget" or "nugetpublish" or "vulcannugetpublish" => ForgeGoal.NuGet,
            _ => throw new ForgeException(
                $"vulcan-forge: goal desconhecido: '{valor}'. Valores aceitos: "
                + "docker-publish (ou docker), nuget-publish (ou nuget)."),
        };
    }
}

/// <summary>
/// Goals que o <b>projeto</b> declara como habilitados.
/// </summary>
/// <remarks>
/// <para>
/// A declaracao e <b>obrigatoria</b> e vive no projeto (<c>vulcanforge.json</c>), nunca na
/// configuracao global: e ela que diz o que aquele projeto tem permissao de publicar. Um goal
/// fora da lista se recusa a rodar.
/// </para>
/// <para>
/// O que isso impede, concretamente: um projeto que so distribui pacote NuGet acabar
/// construindo e empurrando uma imagem Docker porque alguem digitou o comando errado - ou
/// porque um job foi copiado de outro repositorio.
/// </para>
/// </remarks>
public sealed class EnabledGoals
{
    private readonly HashSet<ForgeGoal> _goals;

    private EnabledGoals(HashSet<ForgeGoal> goals) => _goals = goals;

    /// <summary><c>null</c>, lista vazia ou so entradas em branco contam como NAO declarado.</summary>
    public static EnabledGoals Parse(IEnumerable<string>? bruto)
    {
        var conjunto = new HashSet<ForgeGoal>();
        if (bruto is not null)
        {
            foreach (var valor in bruto)
            {
                if (!string.IsNullOrWhiteSpace(valor)) conjunto.Add(ForgeGoals.From(valor));
            }
        }
        return new EnabledGoals(conjunto);
    }

    public bool Declarado => _goals.Count > 0;

    public bool Habilitado(ForgeGoal goal) => _goals.Contains(goal);

    public string Describe() => string.Join(", ", _goals.Select(g => g.CommandName()));

    /// <summary>Recusa a execucao quando o goal nao esta habilitado. E a PRIMEIRA coisa que roda.</summary>
    public void Exigir(ForgeGoal goal)
    {
        if (!Declarado)
        {
            throw new ForgeException(
                "vulcan-forge: nenhum goal habilitado neste projeto.\n\n"
                + "Declare em vulcanforge.json o que este projeto pode publicar:\n\n"
                + "    { \"enabledGoals\": [\"" + goal.CommandName() + "\"] }\n\n"
                + "Os valores possiveis sao:\n"
                + $"    docker-publish   {ForgeGoal.Docker.Description()}\n"
                + $"    nuget-publish    {ForgeGoal.NuGet.Description()}\n\n"
                + "A declaracao e obrigatoria de proposito: sem ela, um comando digitado por "
                + "engano publicaria algo que este projeto nao deveria publicar.");
        }

        if (!Habilitado(goal))
        {
            throw new ForgeException(
                $"vulcan-forge: o goal '{goal.CommandName()}' NAO esta habilitado neste projeto.\n\n"
                + $"  habilitados : {Describe()}\n"
                + $"  pedido      : {goal.CommandName()}  ({goal.Description()})\n\n"
                + "Se isto for intencional, acrescente-o em vulcanforge.json:\n\n"
                + "    { \"enabledGoals\": [" + string.Join(", ",
                    _goals.Select(g => $"\"{g.CommandName()}\"").Append($"\"{goal.CommandName()}\""))
                + "] }");
        }
    }
}

/// <summary>
/// Le configuracao a partir de <b>variaveis de ambiente</b>, com a MESMA regra de nome do lado
/// Java (<c>EnvConfigSource</c>).
/// </summary>
/// <remarks>
/// <para>
/// A regra ser identica nao e simetria gratuita: a esteira desta organizacao exporta as mesmas
/// variaveis para projetos Java e .NET, e um segundo dialeto obrigaria cada job a saber com
/// qual mundo esta falando.
/// </para>
/// <para>
/// Prefixo <c>VULCANFORGE_</c>, ponto vira <c>_</c>, e camelCase vira <c>SNAKE_CASE</c>:
/// </para>
/// <code>
/// vulcanforge.target                  VULCANFORGE_TARGET
/// vulcanforge.dockerfilePath          VULCANFORGE_DOCKERFILE_PATH
/// vulcanforge.nexus.dockerRegistry    VULCANFORGE_NEXUS_DOCKER_REGISTRY
/// vulcanforge.nexus.username          VULCANFORGE_NEXUS_USERNAME
/// vulcanforge.nexus.password          VULCANFORGE_NEXUS_PASSWORD
/// </code>
/// </remarks>
public sealed class EnvConfigSource
{
    public const string EnvPrefix = "VULCANFORGE_";

    private readonly IReadOnlyDictionary<string, string> _env;

    public EnvConfigSource(IReadOnlyDictionary<string, string>? env = null)
        => _env = env ?? LerAmbiente();

    private static Dictionary<string, string> LerAmbiente()
    {
        var mapa = new Dictionary<string, string>(StringComparer.Ordinal);
        foreach (System.Collections.DictionaryEntry e in Environment.GetEnvironmentVariables())
        {
            if (e.Key is string k && e.Value is string v) mapa[k] = v;
        }
        return mapa;
    }

    /// <summary>Nome da variavel de ambiente para uma chave SEM o prefixo <c>vulcanforge.</c>.</summary>
    public static string EnvName(string chave)
    {
        var sb = new StringBuilder(EnvPrefix);
        foreach (var c in chave)
        {
            if (c is '.' or '-')
            {
                sb.Append('_');
            }
            else if (char.IsUpper(c))
            {
                // O `_` so entra se ja nao houver um separador antes, senao `nexus.Docker`
                // viraria `NEXUS__DOCKER`.
                if (sb.Length > EnvPrefix.Length && sb[^1] != '_') sb.Append('_');
                sb.Append(c);
            }
            else
            {
                sb.Append(char.ToUpperInvariant(c));
            }
        }
        return sb.ToString();
    }

    /// <summary>Valor da variavel correspondente, ou <c>null</c> se ausente ou em branco.</summary>
    public string? Get(string chave)
    {
        if (!_env.TryGetValue(EnvName(chave), out var v)) return null;
        return string.IsNullOrWhiteSpace(v) ? null : v.Trim();
    }
}
