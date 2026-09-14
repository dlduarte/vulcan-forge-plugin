using System.Diagnostics;
using System.Text.RegularExpressions;

namespace VulcanForge;

/// <summary>Executor de comandos externos. Existe como interface para os testes nao chamarem docker.</summary>
public interface IProcessRunner
{
    /// <param name="args">argumentos, sem o executavel</param>
    /// <param name="workingDir">diretorio de trabalho, ou <c>null</c> para o atual</param>
    /// <param name="stdin">dados a enviar no stdin (a senha do <c>--password-stdin</c>), ou <c>null</c></param>
    void Exec(IReadOnlyList<string> args, string? workingDir = null, string? stdin = null);

    /// <summary>Executa e devolve o codigo de saida, sem lancar.</summary>
    int TryExec(IReadOnlyList<string> args, string? workingDir = null);
}

/// <summary>
/// Executor sobre <see cref="Process"/>. Funciona em Linux e Windows: o <c>docker</c> e
/// resolvido pelo <c>PATH</c>.
/// </summary>
/// <remarks>
/// A saida (stdout + stderr) e transmitida linha a linha para o log. Um codigo de saida
/// diferente de zero lanca <see cref="ForgeException"/>.
/// </remarks>
public sealed class ProcessRunner : IProcessRunner
{
    public const string DefaultExecutable = "docker";

    private readonly IForgeLogger _log;
    private readonly string _executavel;

    public ProcessRunner(IForgeLogger? log = null, string executavel = DefaultExecutable)
    {
        _log = log ?? ConsoleForgeLogger.Instance;
        _executavel = executavel;
    }

    public void Exec(IReadOnlyList<string> args, string? workingDir = null, string? stdin = null)
    {
        _log.Info($"+ {_executavel} {string.Join(" ", Redact(args))}");

        var psi = NovoStartInfo(args, workingDir);
        psi.RedirectStandardInput = stdin is not null;

        using var processo = Process.Start(psi)
            ?? throw new ForgeException($"nao consegui iniciar '{_executavel}'.");

        if (stdin is not null)
        {
            // Sem quebra de linha extra: o `--password-stdin` do docker le tudo ate o EOF, e um
            // `\n` sobrando entraria NA SENHA. O `Close()` e o que sinaliza o fim.
            processo.StandardInput.Write(stdin);
            processo.StandardInput.Close();
        }

        processo.OutputDataReceived += (_, e) => { if (e.Data is not null) _log.Info(e.Data); };
        processo.ErrorDataReceived += (_, e) => { if (e.Data is not null) _log.Info(e.Data); };
        processo.BeginOutputReadLine();
        processo.BeginErrorReadLine();
        processo.WaitForExit();

        if (processo.ExitCode != 0)
        {
            throw new ForgeException(
                $"'{_executavel} {string.Join(" ", Redact(args))}' falhou com codigo {processo.ExitCode}.");
        }
    }

    public int TryExec(IReadOnlyList<string> args, string? workingDir = null)
    {
        try
        {
            var psi = NovoStartInfo(args, workingDir);
            using var processo = Process.Start(psi);
            if (processo is null) return -1;
            processo.WaitForExit();
            return processo.ExitCode;
        }
        catch (Exception)
        {
            // Binario ausente, PATH errado, daemon fora: quem chama trata o codigo, e a
            // mensagem util e escrita la (ver DockerImagePublisher.CheckDockerAvailable).
            return -1;
        }
    }

    private ProcessStartInfo NovoStartInfo(IReadOnlyList<string> args, string? workingDir)
    {
        var psi = new ProcessStartInfo
        {
            FileName = _executavel,
            RedirectStandardOutput = true,
            RedirectStandardError = true,
            UseShellExecute = false,
        };
        // UM argumento por item, e nao uma string montada: assim nao ha shell no meio para
        // reinterpretar espaco ou aspas no valor de um rotulo.
        foreach (var a in args) psi.ArgumentList.Add(a);
        if (workingDir is not null) psi.WorkingDirectory = workingDir;
        return psi;
    }

    /// <summary>
    /// Esconde o que nao pode aparecer no log.
    /// </summary>
    /// <remarks>
    /// O <c>docker login</c> recebe a senha por stdin, entao ela nao esta nos argumentos - mas o
    /// <c>-u</c> traz o usuario, e um <c>--password</c> vindo de fora (que esta ferramenta nao
    /// gera, mas que alguem pode passar) apareceria inteiro. Mascarar aqui e barato.
    /// </remarks>
    internal static IEnumerable<string> Redact(IReadOnlyList<string> args)
    {
        for (var i = 0; i < args.Count; i++)
        {
            var a = args[i];
            if (a is "--password" or "-p" or "--api-key" && i + 1 < args.Count)
            {
                yield return a;
                yield return "***";
                i++;
                continue;
            }
            yield return Regex.Replace(a, "^(--password=|--api-key=).*$", "$1***");
        }
    }
}
