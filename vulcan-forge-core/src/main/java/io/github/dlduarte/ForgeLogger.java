package io.github.dlduarte;

/**
 * Abstracao de log usada pelo core, para que o mesmo codigo possa escrever no
 * log do Maven ({@code org.apache.maven.plugin.logging.Log}) ou do Gradle
 * ({@code org.gradle.api.logging.Logger}) sem depender de nenhuma das duas APIs.
 */
public interface ForgeLogger {

    void info(String message);

    void warn(String message);

    void error(String message);

    /** Logger simples que escreve em stdout/stderr. Util em testes e como fallback. */
    ForgeLogger CONSOLE = new ForgeLogger() {
        @Override
        public void info(String message) {
            System.out.println("[vulcan-forge] " + message);
        }

        @Override
        public void warn(String message) {
            System.out.println("[vulcan-forge][WARN] " + message);
        }

        @Override
        public void error(String message) {
            System.err.println("[vulcan-forge][ERROR] " + message);
        }
    };
}
