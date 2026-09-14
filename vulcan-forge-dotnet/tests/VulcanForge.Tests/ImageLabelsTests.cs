using VulcanForge;
using Xunit;

namespace VulcanForge.Tests;

/// <summary>
/// O rotulo <c>revision</c> e o unico metadado do qual uma esteira de promocao depende: e dele
/// que sai "de qual commit cortar a branch de release". Por isso ele tem teste, e por isso os
/// testes olham ORDEM e PRECEDENCIA, e nao so presenca.
/// </summary>
public class ImageLabelsTests
{
    private static VulcanForgeConfig Cfg(
        bool oci = true,
        string tag = "1.2.3",
        params KeyValuePair<string, string>[] labels)
        => new() { Tag = tag, OciLabels = oci, Labels = labels };

    private static Dictionary<string, string> Env(params (string, string)[] pares)
    {
        var d = new Dictionary<string, string>(StringComparer.Ordinal);
        foreach (var (k, v) in pares) d[k] = v;
        return d;
    }

    [Fact]
    public void GravaOsQuatroRotulosDaOci()
    {
        var labels = ImageLabels.Resolve(
            Cfg(),
            Env(("CI_COMMIT_SHA", "abc123"), ("CI_PROJECT_URL", "http://git/x")),
            new ListForgeLogger());

        Assert.Equal("1.2.3", labels[ImageLabels.OciVersion]);
        Assert.Equal("abc123", labels[ImageLabels.OciRevision]);
        Assert.Equal("http://git/x", labels[ImageLabels.OciSource]);
        Assert.True(labels.ContainsKey(ImageLabels.OciCreated));
    }

    [Fact]
    public void RevisionRespeitaAOrdemDasVariaveis()
    {
        // VULCANFORGE_REVISION vence o CI: e a saida de quem precisa sobrescrever.
        var labels = ImageLabels.Resolve(
            Cfg(),
            Env(("VULCANFORGE_REVISION", "explicito"), ("CI_COMMIT_SHA", "doGitlab")),
            new ListForgeLogger());

        Assert.Equal("explicito", labels[ImageLabels.OciRevision]);
    }

    [Fact]
    public void SemCommitDescoberto_publicaMasAVISA()
    {
        // A imagem sai assim mesmo (a ferramenta roda fora de CI), mas o silencio seria o
        // problema: e assim que se descobre tarde demais.
        var log = new ListForgeLogger();
        var labels = ImageLabels.Resolve(Cfg(), Env(), log);

        Assert.False(labels.ContainsKey(ImageLabels.OciRevision));
        Assert.Contains(log.Avisos, a => a.Contains(ImageLabels.OciRevision));
        Assert.Contains(log.Avisos, a => a.Contains("VULCANFORGE_REVISION"));
    }

    [Fact]
    public void RotuloExplicitoVenceOAutomatico()
    {
        var labels = ImageLabels.Resolve(
            Cfg(true, "1.2.3", new KeyValuePair<string, string>(ImageLabels.OciVersion, "escrito-a-mao")),
            Env(("CI_COMMIT_SHA", "abc")),
            new ListForgeLogger());

        Assert.Equal("escrito-a-mao", labels[ImageLabels.OciVersion]);
    }

    [Fact]
    public void OciDesligado_naoGravaNadaAutomatico_masMantemOsExplicitos()
    {
        var labels = ImageLabels.Resolve(
            Cfg(false, "1.2.3", new KeyValuePair<string, string>("time", "pagamentos")),
            Env(("CI_COMMIT_SHA", "abc")),
            new ListForgeLogger());

        Assert.False(labels.ContainsKey(ImageLabels.OciRevision));
        Assert.False(labels.ContainsKey(ImageLabels.OciVersion));
        Assert.Equal("pagamentos", labels["time"]);
    }

    [Fact]
    public void SourceUrl_montadaNoGitHubActions()
    {
        var labels = ImageLabels.Resolve(
            Cfg(),
            Env(("GITHUB_SHA", "sha"), ("GITHUB_SERVER_URL", "https://github.com"),
                ("GITHUB_REPOSITORY", "dlduarte/x")),
            new ListForgeLogger());

        Assert.Equal("https://github.com/dlduarte/x", labels[ImageLabels.OciSource]);
    }

    [Fact]
    public void CreatedNaoTemFracaoDeSegundo()
    {
        var labels = ImageLabels.Resolve(Cfg(), Env(("CI_COMMIT_SHA", "a")), new ListForgeLogger());
        Assert.Matches(@"^\d{4}-\d{2}-\d{2}T\d{2}:\d{2}:\d{2}Z$", labels[ImageLabels.OciCreated]);
    }

    [Theory]
    [InlineData("a=1,b=2", 2)]
    [InlineData("a=1\nb=2", 2)]
    [InlineData("", 0)]
    [InlineData(null, 0)]
    public void ParseLabels_aceitaVirgulaEQuebraDeLinha(string? texto, int esperado)
        => Assert.Equal(esperado, ImageLabels.ParseLabels(texto).Count);

    [Fact]
    public void ParseLabels_valorPodeConterIgual()
    {
        var pares = ImageLabels.ParseLabels("url=http://x/y?a=1");
        Assert.Equal("http://x/y?a=1", pares[0].Value);
    }

    [Fact]
    public void ParseLabels_parSemIgualEhErro()
    {
        // Ignorar em silencio produziria uma imagem sem o rotulo que alguem acha que configurou.
        var e = Assert.Throws<ForgeException>(() => ImageLabels.ParseLabels("time"));
        Assert.Contains("chave=valor", e.Message);
    }
}
