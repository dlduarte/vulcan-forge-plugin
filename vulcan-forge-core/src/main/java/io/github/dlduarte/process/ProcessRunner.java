package io.github.dlduarte.process;

import io.github.dlduarte.ForgeException;
import io.github.dlduarte.ForgeLogger;

import java.io.BufferedReader;
import java.io.File;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/**
 * Executor de comandos externos (por padrao, o binario {@code docker}) sobre
 * {@link ProcessBuilder}. Funciona em Linux e Windows: o {@code ProcessBuilder}
 * resolve {@code docker}/{@code docker.exe} pelo {@code PATH}.
 *
 * <p>A saida (stdout+stderr combinados) e transmitida linha a linha para o
 * {@link ForgeLogger}. Um exit code diferente de zero lanca {@link ForgeException}.
 */
public class ProcessRunner {

    public static final String DEFAULT_EXECUTABLE = "docker";

    private final ForgeLogger log;
    private final String executable;

    public ProcessRunner(ForgeLogger log) {
        this(log, DEFAULT_EXECUTABLE);
    }

    public ProcessRunner(ForgeLogger log, String executable) {
        this.log = log != null ? log : ForgeLogger.CONSOLE;
        this.executable = executable;
    }

    /**
     * Executa {@code <executable> <args...>}.
     *
     * @param args       argumentos (sem o executavel)
     * @param workingDir diretorio de trabalho, ou {@code null} para o atual
     * @param stdin      dados a enviar no stdin (ex.: senha via --password-stdin),
     *                   ou {@code null}
     */
    public void exec(List<String> args, File workingDir, String stdin) {
        List<String> command = new ArrayList<>(args.size() + 1);
        command.add(executable);
        command.addAll(args);

        log.info("+ " + executable + " " + String.join(" ", redact(args)));

        ProcessBuilder pb = new ProcessBuilder(command);
        if (workingDir != null) {
            pb.directory(workingDir);
        }
        pb.redirectErrorStream(true);

        Process process;
        try {
            process = pb.start();
        } catch (IOException e) {
            throw new ForgeException("Falha ao iniciar '" + executable
                    + "'. Verifique se o Docker esta instalado e disponivel no PATH.", e);
        }

        writeStdin(process, stdin);
        streamOutput(process);

        int exit;
        try {
            exit = process.waitFor();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            process.destroy();
            throw new ForgeException("Execucao interrompida: " + executable, e);
        }

        if (exit != 0) {
            throw new ForgeException("Comando falhou (exit code " + exit + "): "
                    + executable + " " + String.join(" ", redact(args)));
        }
    }

    /**
     * Executa {@code <executable> <args...>} silenciosamente (sem logar a saida) e retorna
     * o exit code. Nao lanca excecao: retorna {@code -1} se o processo nao pode ser iniciado.
     * Util para preflight (ex.: {@code docker version}).
     */
    public int tryExec(List<String> args) {
        List<String> command = new ArrayList<>(args.size() + 1);
        command.add(executable);
        command.addAll(args);
        ProcessBuilder pb = new ProcessBuilder(command);
        pb.redirectErrorStream(true);
        try {
            Process process = pb.start();
            process.getOutputStream().close();
            try (BufferedReader reader = new BufferedReader(
                    new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8))) {
                while (reader.readLine() != null) {
                    // descarta a saida
                }
            }
            return process.waitFor();
        } catch (IOException e) {
            return -1;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return -1;
        }
    }

    private void writeStdin(Process process, String stdin) {
        try (OutputStream os = process.getOutputStream()) {
            if (stdin != null) {
                os.write(stdin.getBytes(StandardCharsets.UTF_8));
                os.flush();
            }
        } catch (IOException e) {
            throw new ForgeException("Falha ao escrever no stdin do processo: " + executable, e);
        }
    }

    private void streamOutput(Process process) {
        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                log.info(line);
            }
        } catch (IOException e) {
            log.warn("Falha ao ler a saida do processo: " + e.getMessage());
        }
    }

    /** Nao registra o valor apos {@code -p}/{@code --password} caso apareca na linha. */
    private static List<String> redact(List<String> args) {
        List<String> out = new ArrayList<>(args.size());
        boolean redactNext = false;
        for (String a : args) {
            if (redactNext) {
                out.add("***");
                redactNext = false;
                continue;
            }
            out.add(a);
            if (a.equals("-p") || a.equals("--password")) {
                redactNext = true;
            }
        }
        return out;
    }
}
