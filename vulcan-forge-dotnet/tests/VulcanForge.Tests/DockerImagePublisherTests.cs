using VulcanForge;
using Xunit;

namespace VulcanForge.Tests;

/// <summary>Grava os comandos em vez de executa-los. Nenhum teste chama docker.</summary>
internal sealed class FakeRunner : IProcessRunner
{
    public List<IReadOnlyList<string>> Comandos { get; } = new();
    public List<string?> Stdins { get; } = new();
    public int CodigoDoTryExec { get; set; }

    public void Exec(IReadOnlyList<string> args, string? workingDir = null, string? stdin = null)
    {
        Comandos.Add(args);
        Stdins.Add(stdin);
    }

    public int TryExec(IReadOnlyList<string> args, string? workingDir = null) => CodigoDoTryExec;

    public IReadOnlyList<string> Comando(string primeiroArgumento)
        => Comandos.First(c => c[0] == primeiroArgumento);
}

public class DockerImagePublisherTests : IDisposable
{
    private readonly string _dir;

    public DockerImagePublisherTests()
    {
        _dir = Path.Combine(Path.GetTempPath(), "vf-" + Guid.NewGuid().ToString("N")[..8]);
        Directory.CreateDirectory(_dir);
        File.WriteAllText(Path.Combine(_dir, "Dockerfile"), "FROM scratch\n");
    }

    public void Dispose()
    {
        try { Directory.Delete(_dir, true); } catch (IOException) { /* temp */ }
    }

    private VulcanForgeConfig Cfg(Action<VulcanForgeConfigBuilder>? ajustar = null)
    {
        var b = new VulcanForgeConfigBuilder
        {
            DockerRegistry = "192.168.15.34:5000",
            Namespace = "br-com-soften/servicos-hibridos",
            ImageName = "averba-senig",
            Tag = "1.0.0",
            ServerId = "nexus",
        };
        ajustar?.Invoke(b);
        return b.Build();
    }

    private static Dictionary<string, string> Env(params (string, string)[] pares)
    {
        var d = new Dictionary<string, string>(StringComparer.Ordinal);
        foreach (var (k, v) in pares) d[k] = v;
        return d;
    }

    [Fact]
    public void MontaARefRemotaCompleta()
        => Assert.Equal(
            "192.168.15.34:5000/br-com-soften/servicos-hibridos/averba-senig:1.0.0",
            DockerImagePublisher.BuildRemoteRef(Cfg()));

    [Fact]
    public void RegistryHost_tiraOEsquemaEOCaminho()
    {
        // O Docker nao aceita esquema na referencia da imagem.
        Assert.Equal("nexus.exemplo:8083",
            DockerImagePublisher.RegistryHost(Cfg(b => b.DockerRegistry = "https://nexus.exemplo:8083/repo")));
    }

    [Fact]
    public void OsQuatroPassos_naOrdem()
    {
        var runner = new FakeRunner();
        new DockerImagePublisher(runner, new ListForgeLogger())
            .Publish(Cfg(), new Credentials("u", "p"), _dir, Env(("CI_COMMIT_SHA", "abc")));

        Assert.Equal(new[] { "build", "tag", "login", "push", "rmi" },
            runner.Comandos.Select(c => c[0]).ToArray());
    }

    [Fact]
    public void OsRotulosEntramNoBuild()
    {
        var runner = new FakeRunner();
        new DockerImagePublisher(runner, new ListForgeLogger())
            .Publish(Cfg(), new Credentials("u", "p"), _dir, Env(("CI_COMMIT_SHA", "abc123")));

        var build = runner.Comando("build");
        Assert.Contains($"{ImageLabels.OciRevision}=abc123", build);
    }

    [Fact]
    public void ASenhaVaiPorStdin_nuncaNosArgumentos()
    {
        // E o que a mantem fora de `ps` e de qualquer log que ecoe o comando.
        var runner = new FakeRunner();
        new DockerImagePublisher(runner, new ListForgeLogger())
            .Publish(Cfg(), new Credentials("u", "senha-secreta"), _dir, Env());

        var login = runner.Comando("login");
        Assert.Contains("--password-stdin", login);
        Assert.DoesNotContain(login, a => a.Contains("senha-secreta"));
        Assert.Contains("senha-secreta", runner.Stdins[runner.Comandos.IndexOf(login)]);
    }

    [Fact]
    public void SemCredencial_avisaEPulaOLogin()
    {
        var log = new ListForgeLogger();
        var runner = new FakeRunner();
        new DockerImagePublisher(runner, log).Publish(Cfg(), null, _dir, Env());

        Assert.DoesNotContain(runner.Comandos, c => c[0] == "login");
        Assert.Contains(log.Avisos, a => a.Contains("credenciais", StringComparison.OrdinalIgnoreCase));
    }

    [Fact]
    public void DockerTarget_viraTargetNoBuild()
    {
        var runner = new FakeRunner();
        new DockerImagePublisher(runner, new ListForgeLogger())
            .Publish(Cfg(b => b.DockerTarget = "aplicacao"), null, _dir, Env());

        var build = runner.Comando("build");
        Assert.Contains("--target", build);
        Assert.Contains("aplicacao", build);
    }

    [Fact]
    public void RemoveLocalImageDesligado_naoRodaRmi()
    {
        var runner = new FakeRunner();
        new DockerImagePublisher(runner, new ListForgeLogger())
            .Publish(Cfg(b => b.RemoveLocalImage = false), null, _dir, Env());

        Assert.DoesNotContain(runner.Comandos, c => c[0] == "rmi");
    }

    [Fact]
    public void DockerfileAusente_falaOndeProcurou()
    {
        File.Delete(Path.Combine(_dir, "Dockerfile"));
        var e = Assert.Throws<ForgeException>(() =>
            new DockerImagePublisher(new FakeRunner(), new ListForgeLogger())
                .Publish(Cfg(), null, _dir, Env()));

        Assert.Contains("Dockerfile nao encontrado", e.Message);
    }

    [Fact]
    public void SemRegistry_dizQualVariavelExportar()
    {
        var e = Assert.Throws<ForgeException>(() =>
            new DockerImagePublisher(new FakeRunner(), new ListForgeLogger())
                .Publish(Cfg(b => b.DockerRegistry = null), null, _dir, Env()));

        Assert.Contains("VULCANFORGE_NEXUS_DOCKER_REGISTRY", e.Message);
    }

    [Fact]
    public void SemTag_dizQueAEsteiraPassaTag()
    {
        var e = Assert.Throws<ForgeException>(() =>
            new DockerImagePublisher(new FakeRunner(), new ListForgeLogger())
                .Publish(Cfg(b => b.Tag = null), null, _dir, Env()));

        Assert.Contains("--tag", e.Message);
    }

    [Fact]
    public void DockerIndisponivel_explicaOQueConferir()
    {
        var runner = new FakeRunner { CodigoDoTryExec = 127 };
        var e = Assert.Throws<ForgeException>(
            () => new DockerImagePublisher(runner, new ListForgeLogger()).CheckDockerAvailable());

        Assert.Contains("daemon", e.Message);
    }

    [Fact]
    public void RmiQueFalha_naoInvalidaAPublicacao()
    {
        // A imagem JA foi empurrada; falhar aqui reportaria como erro um release que deu certo.
        var runner = new RunnerQueFalhaNo("rmi");
        var log = new ListForgeLogger();
        new DockerImagePublisher(runner, log).Publish(Cfg(), null, _dir, Env());

        Assert.Contains(log.Avisos, a => a.Contains("publicacao FOI concluida"));
    }

    private sealed class RunnerQueFalhaNo : IProcessRunner
    {
        private readonly string _comando;
        public RunnerQueFalhaNo(string comando) => _comando = comando;

        public void Exec(IReadOnlyList<string> args, string? workingDir = null, string? stdin = null)
        {
            if (args[0] == _comando) throw new ForgeException("falhou de proposito");
        }

        public int TryExec(IReadOnlyList<string> args, string? workingDir = null) => 0;
    }
}

/// <summary>Construtor mutavel, so para os testes montarem variacoes sem repetir tudo.</summary>
internal sealed class VulcanForgeConfigBuilder
{
    public RegistryTarget Target { get; set; } = RegistryTarget.Nexus;
    public string? DockerRegistry { get; set; }
    public string? Namespace { get; set; }
    public string? ImageName { get; set; }
    public string? Tag { get; set; }
    public string DockerfilePath { get; set; } = "Dockerfile";
    public string ContextPath { get; set; } = ".";
    public string? DockerTarget { get; set; }
    public string? ServerId { get; set; }
    public bool RemoveLocalImage { get; set; } = true;
    public bool OciLabels { get; set; } = true;

    public VulcanForgeConfig Build() => new()
    {
        Target = Target,
        DockerRegistry = DockerRegistry,
        Namespace = Namespace,
        ImageName = ImageName,
        Tag = Tag,
        DockerfilePath = DockerfilePath,
        ContextPath = ContextPath,
        DockerTarget = DockerTarget,
        ServerId = ServerId,
        RemoveLocalImage = RemoveLocalImage,
        OciLabels = OciLabels,
    };
}

public class ProcessRunnerRedactTests
{
    [Fact]
    public void MascaraSenhaEApiKey()
    {
        var saida = ProcessRunner.Redact(new[] { "push", "--api-key", "chave-real", "--password=abc" })
            .ToList();

        Assert.DoesNotContain("chave-real", saida);
        Assert.Contains("***", saida);
        Assert.Contains("--password=***", saida);
    }
}

public class CliTests
{
    [Fact]
    public void ArgumentosKebab_viramChavesCamelCase()
    {
        var d = Cli.ParsearArgumentos(new[] { "--image-name", "api", "--dockerfile-path=infra/Dockerfile" });
        Assert.Equal("api", d["imageName"]);
        Assert.Equal("infra/Dockerfile", d["dockerfilePath"]);
    }

    [Fact]
    public void FlagSemValor_contaComoTrue()
        => Assert.Equal("true", Cli.ParsearArgumentos(new[] { "--oci-labels" })["ociLabels"]);

    [Fact]
    public void ArgumentoSolto_ehErroExplicado()
    {
        var e = Assert.Throws<ForgeException>(() => Cli.ParsearArgumentos(new[] { "solto" }));
        Assert.Contains("--chave valor", e.Message);
    }
}
