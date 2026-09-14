namespace VulcanForge;

/// <summary>
/// Falha esperada do Vulcan Forge: configuracao ausente, goal nao habilitado, comando externo
/// que retornou codigo diferente de zero.
/// </summary>
/// <remarks>
/// Ela e capturada no <c>Program</c> e vira uma mensagem de UMA linha com codigo de saida 1 -
/// sem stack trace. Quem le o log de um job vermelho as seis da tarde precisa da causa, e nao
/// do caminho ate ela. Erro NAO esperado continua subindo com o stack trace inteiro, porque ali
/// o caminho e a informacao.
/// </remarks>
public sealed class ForgeException : Exception
{
    public ForgeException(string mensagem) : base(mensagem) { }
}

/// <summary>Saida do Vulcan Forge.</summary>
public interface IForgeLogger
{
    void Info(string mensagem);
    void Warn(string mensagem);
}

/// <summary>Escreve no console: <c>Info</c> em stdout, <c>Warn</c> em stderr.</summary>
public sealed class ConsoleForgeLogger : IForgeLogger
{
    public static readonly ConsoleForgeLogger Instance = new();

    public void Info(string mensagem) => Console.WriteLine(mensagem);

    // stderr, e nao stdout: assim um `| grep` no log do job nao engole o aviso.
    public void Warn(string mensagem) => Console.Error.WriteLine("AVISO: " + mensagem);
}

/// <summary>Coleta as mensagens em memoria. Existe para os testes.</summary>
public sealed class ListForgeLogger : IForgeLogger
{
    public List<string> Infos { get; } = new();
    public List<string> Avisos { get; } = new();

    public void Info(string mensagem) => Infos.Add(mensagem);
    public void Warn(string mensagem) => Avisos.Add(mensagem);
}

/// <summary>Par usuario/senha para o <c>docker login</c> ou para o feed NuGet.</summary>
public sealed class Credentials
{
    public Credentials(string? usuario, string? senha)
    {
        Usuario = usuario;
        Senha = senha;
    }

    public string? Usuario { get; }
    public string? Senha { get; }

    public bool Completa => !string.IsNullOrWhiteSpace(Usuario) && !string.IsNullOrWhiteSpace(Senha);

    // Nunca exponha a senha em log.
    public override string ToString() => $"Credentials{{usuario='{Usuario}', senha='***'}}";
}
