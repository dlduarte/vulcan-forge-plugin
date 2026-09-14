using System.Text.Json;
using System.Text.Json.Serialization;

namespace VulcanForge;

/// <summary>Configuracao resolvida (imutavel) para um target especifico.</summary>
public sealed class VulcanForgeConfig
{
    public RegistryTarget Target { get; init; } = RegistryTarget.Nexus;

    /// <summary>Host do registry Docker (ex.: <c>192.168.15.34:5000</c>, <c>ghcr.io</c>).</summary>
    public string? DockerRegistry { get; init; }

    /// <summary>URL do feed NuGet de destino.</summary>
    public string? NuGetUrl { get; init; }

    public string? Namespace { get; init; }
    public string? ImageName { get; init; }
    public string? Tag { get; init; }
    public string DockerfilePath { get; init; } = "Dockerfile";

    /// <summary>Contexto do <c>docker build</c>, relativo ao diretorio do projeto.</summary>
    public string ContextPath { get; init; } = ".";

    /// <summary>Alvo do Dockerfile multi-estagio (<c>--target</c>). Vazio = o ultimo estagio.</summary>
    public string? DockerTarget { get; init; }

    /// <summary>Id das credenciais - o prefixo das variaveis de ambiente.</summary>
    public string? ServerId { get; init; }

    /// <summary>Remove a imagem local (tags local e remota) apos o push. Padrao: <c>true</c>.</summary>
    public bool RemoveLocalImage { get; init; } = true;

    /// <summary>
    /// Rotulos explicitos, na ordem de declaracao. Eles VENCEM os automaticos da OCI: quem
    /// configurou um valor a mao disse o que queria.
    /// </summary>
    public IReadOnlyList<KeyValuePair<string, string>> Labels { get; init; }
        = Array.Empty<KeyValuePair<string, string>>();

    /// <summary>
    /// Escreve os rotulos padrao da OCI. Padrao: <c>true</c>, e ligado de proposito - ver
    /// <see cref="ImageLabels"/>.
    /// </summary>
    public bool OciLabels { get; init; } = true;

    /// <summary>Argumentos <c>--build-arg</c>, na ordem de declaracao.</summary>
    public IReadOnlyList<KeyValuePair<string, string>> BuildArgs { get; init; }
        = Array.Empty<KeyValuePair<string, string>>();

    public override string ToString() =>
        $"VulcanForgeConfig{{target={Target}, dockerRegistry='{DockerRegistry}', "
        + $"namespace='{Namespace}', imageName='{ImageName}', tag='{Tag}', "
        + $"dockerfilePath='{DockerfilePath}', contextPath='{ContextPath}', "
        + $"dockerTarget='{DockerTarget}', serverId='{ServerId}', "
        + $"removeLocalImage={RemoveLocalImage}, ociLabels={OciLabels}}}";
}

/// <summary>
/// O que o <b>projeto</b> declara, em <c>vulcanforge.json</c>.
/// </summary>
/// <remarks>
/// <para>
/// E o equivalente do <c>&lt;configuration&gt;</c> do <c>pom.xml</c> e do bloco
/// <c>vulcanForge { }</c> do Gradle: a <b>identidade</b> do artefato e a lista de goals
/// permitidos. As <b>coordenadas do servidor</b> e as credenciais NAO moram aqui - elas sao
/// globais e vem do ambiente.
/// </para>
/// <para>
/// <b>Por que um arquivo proprio, e nao propriedades do <c>.csproj</c>:</b> ler propriedade de
/// MSBuild de fora do MSBuild exige interpretar imports e condicoes, ou chamar
/// <c>dotnet msbuild -getProperty</c> a cada execucao. As duas trocam um arquivo simples e
/// versionado por um acoplamento fragil, e nenhuma das duas melhora o que importa aqui - que a
/// declaracao esteja num diff que alguem revisa.
/// </para>
/// </remarks>
public sealed class ProjectConfig
{
    public const string FileName = "vulcanforge.json";

    [JsonPropertyName("enabledGoals")]
    public List<string>? EnabledGoals { get; set; }

    [JsonPropertyName("target")]
    public string? Target { get; set; }

    [JsonPropertyName("namespace")]
    public string? Namespace { get; set; }

    [JsonPropertyName("imageName")]
    public string? ImageName { get; set; }

    [JsonPropertyName("tag")]
    public string? Tag { get; set; }

    [JsonPropertyName("dockerfilePath")]
    public string? DockerfilePath { get; set; }

    [JsonPropertyName("contextPath")]
    public string? ContextPath { get; set; }

    [JsonPropertyName("dockerTarget")]
    public string? DockerTarget { get; set; }

    [JsonPropertyName("removeLocalImage")]
    public bool? RemoveLocalImage { get; set; }

    [JsonPropertyName("ociLabels")]
    public bool? OciLabels { get; set; }

    [JsonPropertyName("labels")]
    public Dictionary<string, string>? Labels { get; set; }

    /// <summary>
    /// Le o arquivo do diretorio informado. Ausente devolve uma instancia vazia - a falta do
    /// arquivo e reportada depois, pelo <see cref="EnabledGoals.Exigir"/>, com uma mensagem
    /// que diz o que escrever nele.
    /// </summary>
    public static ProjectConfig Load(string diretorio)
    {
        var caminho = Path.Combine(diretorio, FileName);
        if (!File.Exists(caminho)) return new ProjectConfig();

        try
        {
            var opcoes = new JsonSerializerOptions
            {
                // Comentario e virgula sobrando: o arquivo e escrito e lido por gente, e
                // recusa-lo por uma virgula custa mais do que aceita-la.
                ReadCommentHandling = JsonCommentHandling.Skip,
                AllowTrailingCommas = true,
                PropertyNameCaseInsensitive = true,
            };
            return JsonSerializer.Deserialize<ProjectConfig>(File.ReadAllText(caminho), opcoes)
                   ?? new ProjectConfig();
        }
        catch (JsonException e)
        {
            throw new ForgeException($"{caminho} nao e um JSON valido: {e.Message}");
        }
    }
}
