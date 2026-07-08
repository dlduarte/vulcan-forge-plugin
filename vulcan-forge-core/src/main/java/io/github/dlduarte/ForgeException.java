package io.github.dlduarte;

/**
 * Excecao de tempo de execucao para falhas nas operacoes do Vulcan Forge
 * (resolucao de configuracao, execucao de comandos Docker, etc.).
 */
public class ForgeException extends RuntimeException {

    public ForgeException(String message) {
        super(message);
    }

    public ForgeException(String message, Throwable cause) {
        super(message, cause);
    }
}
