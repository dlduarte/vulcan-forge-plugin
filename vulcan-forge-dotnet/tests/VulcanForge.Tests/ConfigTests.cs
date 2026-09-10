using VulcanForge;
using Xunit;

namespace VulcanForge.Tests;

/// <summary>
/// A regra de nome das variaveis tem que ser IDENTICA a do lado Java: a esteira exporta as
/// mesmas variaveis para projetos Java e .NET, e um segundo dialeto obrigaria cada job a saber
/// com qual mundo esta falando.
/// </summary>
public class EnvConfigSourceTests
{
    [Theory]
    [InlineData("target", "VULCANFORGE_TARGET")]
    [InlineData("dockerfilePath", "VULCANFORGE_DOCKERFILE_PATH")]
    [InlineData("nexus.dockerRegistry", "VULCANFORGE_NEXUS_DOCKER_REGISTRY")]
    [InlineData("nexus.serverId", "VULCANFORGE_NEXUS_SERVER_ID")]
    [InlineData("nexus.username", "VULCANFORGE_NEXUS_USERNAME")]
    [InlineData("nexus.password", "VULCANFORGE_NEXUS_PASSWORD")]
    [InlineData("removeLocalImage", "VULCANFORGE_REMOVE_LOCAL_IMAGE")]
    public void EnvName_seguemAMesmaRegraDoLadoJava(string chave, string esperado)
        => Assert.Equal(esperado, EnvConfigSource.EnvName(chave));

    [Fact]
    public void EnvName_naoDuplicaSeparador()
    {
        // `nexus.Docker` nao pode virar NEXUS__DOCKER.
        Assert.Equal("VULCANFORGE_NEXUS_DOCKER", EnvConfigSource.EnvName("nexus.Docker"));
    }

    [Fact]
    public void ValorEmBrancoContaComoAusente()
    {
        var env = new EnvConfigSource(new Dictionary<string, string> { ["VULCANFORGE_TAG"] = "   " });
        Assert.Null(env.Get("tag"));
    }
}

public class EnabledGoalsTests
{
    [Fact]
    public void SemDeclaracao_recusaEExplicaOQueEscrever()
    {
        var e = Assert.Throws<ForgeException>(
            () => EnabledGoals.Parse(null).Exigir(ForgeGoal.Docker));

        Assert.Contains("vulcanforge.json", e.Message);
        Assert.Contains("docker-publish", e.Message);
    }

    [Fact]
    public void GoalNaoHabilitado_recusaEMostraOQueEstaHabilitado()
    {
        var goals = EnabledGoals.Parse(new[] { "nuget-publish" });
        var e = Assert.Throws<ForgeException>(() => goals.Exigir(ForgeGoal.Docker));

        Assert.Contains("NAO esta habilitado", e.Message);
        Assert.Contains("nuget-publish", e.Message);
    }

    [Fact]
    public void GoalHabilitado_passa()
        => EnabledGoals.Parse(new[] { "docker-publish" }).Exigir(ForgeGoal.Docker);

    [Theory]
    [InlineData("docker")]
    [InlineData("docker-publish")]
    [InlineData("dockerPublish")] // a task do Gradle: quem migra nao reescreve a declaracao
    [InlineData("DOCKER_PUBLISH")]
    public void AceitaOsApelidosDosTresMundos(string valor)
        => Assert.Equal(ForgeGoal.Docker, ForgeGoals.From(valor));

    [Fact]
    public void GoalDesconhecido_falaOQueEhAceito()
    {
        var e = Assert.Throws<ForgeException>(() => ForgeGoals.From("maven-publish"));
        Assert.Contains("nuget-publish", e.Message);
    }

    [Fact]
    public void EntradasEmBrancoNaoContamComoDeclaracao()
        => Assert.False(EnabledGoals.Parse(new[] { "", "   " }).Declarado);
}

public class ConfigResolverTests
{
    private static ConfigResolver Resolver(params (string, string)[] env)
    {
        var d = new Dictionary<string, string>(StringComparer.Ordinal);
        foreach (var (k, v) in env) d[k] = v;
        return new ConfigResolver(new EnvConfigSource(d));
    }

    private static readonly Dictionary<string, string> SemPadroes = new();

    [Fact]
    public void LinhaDeComando_venceProjetoEAmbiente()
    {
        var cfg = Resolver(("VULCANFORGE_TAG", "doAmbiente"))
            .Resolve(
                new Dictionary<string, string> { ["tag"] = "daLinhaDeComando" },
                new ProjectConfig { Tag = "doProjeto" },
                SemPadroes);

        Assert.Equal("daLinhaDeComando", cfg.Tag);
    }

    [Fact]
    public void Projeto_venceAmbiente()
    {
        // A configuracao escrita e explicita; o ambiente muda sem ninguem editar nada.
        var cfg = Resolver(("VULCANFORGE_IMAGE_NAME", "doAmbiente"))
            .Resolve(new Dictionary<string, string>(),
                new ProjectConfig { ImageName = "doProjeto" }, SemPadroes);

        Assert.Equal("doProjeto", cfg.ImageName);
    }

    [Fact]
    public void Ambiente_venceOPadraoDerivadoDoProjeto()
    {
        var cfg = Resolver(("VULCANFORGE_TAG", "doAmbiente"))
            .Resolve(new Dictionary<string, string>(), new ProjectConfig(),
                new Dictionary<string, string> { ["tag"] = "daVersionDoCsproj" });

        Assert.Equal("doAmbiente", cfg.Tag);
    }

    [Fact]
    public void CoordenadasSaemSoDoAmbiente()
    {
        // O projeto NAO declara registry nem credencial, de proposito.
        var cfg = Resolver(("VULCANFORGE_NEXUS_DOCKER_REGISTRY", "192.168.15.34:5000"))
            .Resolve(new Dictionary<string, string>(), new ProjectConfig(), SemPadroes);

        Assert.Equal("192.168.15.34:5000", cfg.DockerRegistry);
    }

    [Fact]
    public void TargetGithub_traiOsPadroesDele()
    {
        var cfg = Resolver().Resolve(new Dictionary<string, string>(),
            new ProjectConfig { Target = "github" }, SemPadroes);

        Assert.Equal(RegistryTarget.GitHubPackages, cfg.Target);
        Assert.Equal("ghcr.io", cfg.DockerRegistry);
    }

    [Fact]
    public void Credenciais_saemDoServerIdNoAmbiente()
    {
        var resolver = Resolver(
            ("VULCANFORGE_NEXUS_USERNAME", "u"), ("VULCANFORGE_NEXUS_PASSWORD", "p"));
        var cfg = resolver.Resolve(new Dictionary<string, string>(), new ProjectConfig(), SemPadroes);

        var creds = resolver.ResolveCredentials(cfg);
        Assert.True(creds.Completa);
        Assert.Equal("u", creds.Usuario);
        Assert.DoesNotContain("p", creds.ToString()); // nunca no log
    }

    [Fact]
    public void OciLabelsLigadoPorPadrao()
    {
        var cfg = Resolver().Resolve(new Dictionary<string, string>(), new ProjectConfig(), SemPadroes);
        Assert.True(cfg.OciLabels);
    }

    [Fact]
    public void OciLabels_desligaExplicitamente()
    {
        var cfg = Resolver().Resolve(
            new Dictionary<string, string> { ["ociLabels"] = "false" }, new ProjectConfig(), SemPadroes);
        Assert.False(cfg.OciLabels);
    }

    [Fact]
    public void BooleanoInvalido_falaOQueEhAceito()
    {
        var e = Assert.Throws<ForgeException>(() => Resolver().Resolve(
            new Dictionary<string, string> { ["ociLabels"] = "talvez" }, new ProjectConfig(), SemPadroes));
        Assert.Contains("true ou false", e.Message);
    }

    [Fact]
    public void LabelsDoProjetoVencemOsDaLinhaDeComando()
    {
        // A forma de MAPA e a configuracao mais especifica que existe.
        var cfg = Resolver().Resolve(
            new Dictionary<string, string> { ["labels"] = "time=antigo,tier=api" },
            new ProjectConfig { Labels = new() { ["time"] = "novo" } },
            SemPadroes);

        Assert.Equal("novo", cfg.Labels.First(p => p.Key == "time").Value);
        Assert.Equal("api", cfg.Labels.First(p => p.Key == "tier").Value);
    }
}
