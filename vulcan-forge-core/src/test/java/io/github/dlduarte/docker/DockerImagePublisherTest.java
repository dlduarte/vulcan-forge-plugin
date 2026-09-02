package io.github.dlduarte.docker;

import io.github.dlduarte.ForgeException;
import io.github.dlduarte.ForgeLogger;
import io.github.dlduarte.config.Credentials;
import io.github.dlduarte.config.RegistryTarget;
import io.github.dlduarte.config.VulcanForgeConfig;
import io.github.dlduarte.process.ProcessRunner;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DockerImagePublisherTest {

    /** ProcessRunner falso que apenas registra os comandos recebidos. */
    static class RecordingRunner extends ProcessRunner {
        final List<List<String>> commands = new ArrayList<>();
        final List<String> stdins = new ArrayList<>();

        RecordingRunner() {
            super(ForgeLogger.CONSOLE);
        }

        @Override
        public void exec(List<String> args, File workingDir, String stdin) {
            commands.add(new ArrayList<>(args));
            stdins.add(stdin);
        }
    }

    private VulcanForgeConfig nexusConfig() {
        return VulcanForgeConfig.builder()
                .target(RegistryTarget.NEXUS)
                .dockerRegistry("nexus.example.com:8083")
                .namespace("meu-time")
                .imageName("meu-app")
                .tag("1.0.0")
                .dockerfilePath("Dockerfile")
                .serverId("nexus-docker")
                .build();
    }

    /** Config sem serverId (push sem login). */
    private VulcanForgeConfig noAuthConfig() {
        return VulcanForgeConfig.builder()
                .target(RegistryTarget.NEXUS)
                .dockerRegistry("nexus.example.com:8083")
                .namespace("meu-time")
                .imageName("meu-app")
                .tag("1.0.0")
                .dockerfilePath("Dockerfile")
                .build();
    }

    @Test
    void runsBuildTagLoginPushRemoveInOrder(@TempDir Path projectDir) throws IOException {
        Files.writeString(projectDir.resolve("Dockerfile"), "FROM eclipse-temurin:17-jre\n");
        RecordingRunner runner = new RecordingRunner();
        DockerImagePublisher pub = new DockerImagePublisher(runner, ForgeLogger.CONSOLE);

        pub.publish(nexusConfig(), new Credentials("user", "pass"), projectDir.toFile());

        // build, tag, login, push, rmi (removeLocalImage=true por padrao)
        assertEquals(5, runner.commands.size());
        assertEquals("build", runner.commands.get(0).get(0));
        assertEquals("tag", runner.commands.get(1).get(0));
        assertEquals("login", runner.commands.get(2).get(0));
        assertEquals("push", runner.commands.get(3).get(0));
        assertEquals("rmi", runner.commands.get(4).get(0));

        // tag aponta para a ref remota completa
        List<String> tag = runner.commands.get(1);
        assertEquals("meu-app:1.0.0", tag.get(1));
        assertEquals("nexus.example.com:8083/meu-time/meu-app:1.0.0", tag.get(2));

        // senha enviada via stdin, nao como argumento
        assertEquals("pass", runner.stdins.get(2));
        assertTrue(runner.commands.get(2).contains("--password-stdin"));

        // push usa a ref remota
        assertEquals("nexus.example.com:8083/meu-time/meu-app:1.0.0", runner.commands.get(3).get(1));

        // rmi remove ambas as tags (local e remota)
        assertTrue(runner.commands.get(4).contains("meu-app:1.0.0"));
        assertTrue(runner.commands.get(4).contains("nexus.example.com:8083/meu-time/meu-app:1.0.0"));
    }

    @Test
    void skipsLoginWhenNoServerId(@TempDir Path projectDir) throws IOException {
        Files.writeString(projectDir.resolve("Dockerfile"), "FROM scratch\n");
        RecordingRunner runner = new RecordingRunner();
        DockerImagePublisher pub = new DockerImagePublisher(runner, ForgeLogger.CONSOLE);

        pub.publish(noAuthConfig(), null, projectDir.toFile());

        // build, tag, push, rmi (sem login)
        assertEquals(4, runner.commands.size());
        assertEquals("build", runner.commands.get(0).get(0));
        assertEquals("tag", runner.commands.get(1).get(0));
        assertEquals("push", runner.commands.get(2).get(0));
        assertEquals("rmi", runner.commands.get(3).get(0));
    }

    @Test
    void doesNotRemoveWhenRemoveLocalImageFalse(@TempDir Path projectDir) throws IOException {
        Files.writeString(projectDir.resolve("Dockerfile"), "FROM scratch\n");
        VulcanForgeConfig cfg = VulcanForgeConfig.builder()
                .target(RegistryTarget.NEXUS)
                .dockerRegistry("nexus.example.com:8083")
                .imageName("meu-app").tag("1.0.0").dockerfilePath("Dockerfile")
                .removeLocalImage(false)
                .build();
        RecordingRunner runner = new RecordingRunner();
        DockerImagePublisher pub = new DockerImagePublisher(runner, ForgeLogger.CONSOLE);

        pub.publish(cfg, null, projectDir.toFile());

        assertEquals(3, runner.commands.size()); // build, tag, push (sem rmi)
        assertTrue(runner.commands.stream().noneMatch(c -> c.get(0).equals("rmi")));
    }

    @Test
    void failsWhenDockerfileMissing(@TempDir Path projectDir) {
        RecordingRunner runner = new RecordingRunner();
        DockerImagePublisher pub = new DockerImagePublisher(runner, ForgeLogger.CONSOLE);

        ForgeException ex = assertThrows(ForgeException.class,
                () -> pub.publish(noAuthConfig(), null, projectDir.toFile()));
        assertTrue(ex.getMessage().contains("Dockerfile"));
    }

    @Test
    void failsFastWhenDockerRegistryMissing(@TempDir Path projectDir) throws IOException {
        Files.writeString(projectDir.resolve("Dockerfile"), "FROM scratch\n");
        VulcanForgeConfig cfg = VulcanForgeConfig.builder()
                .target(RegistryTarget.NEXUS)
                .imageName("meu-app").tag("1.0.0").dockerfilePath("Dockerfile")
                .build(); // sem dockerRegistry
        RecordingRunner runner = new RecordingRunner();
        DockerImagePublisher pub = new DockerImagePublisher(runner, ForgeLogger.CONSOLE);

        ForgeException ex = assertThrows(ForgeException.class,
                () -> pub.validate(cfg, null, projectDir.toFile()));
        assertTrue(ex.getMessage().contains("dockerRegistry"));
    }

    @Test
    void failsFastWhenServerIdSetButCredentialsMissing(@TempDir Path projectDir) throws IOException {
        Files.writeString(projectDir.resolve("Dockerfile"), "FROM scratch\n");
        RecordingRunner runner = new RecordingRunner();
        DockerImagePublisher pub = new DockerImagePublisher(runner, ForgeLogger.CONSOLE);

        // nexusConfig tem serverId 'nexus-docker' mas passamos creds nulas
        ForgeException ex = assertThrows(ForgeException.class,
                () -> pub.validate(nexusConfig(), null, projectDir.toFile()));
        assertTrue(ex.getMessage().contains("nexus-docker"));
    }

    @Test
    void buildRemoteRefStripsScheme() {
        VulcanForgeConfig cfg = VulcanForgeConfig.builder()
                .target(RegistryTarget.GITHUB_PACKAGES)
                .dockerRegistry("https://ghcr.io")
                .namespace("owner")
                .imageName("app")
                .tag("2.0")
                .build();

        assertEquals("ghcr.io/owner/app:2.0", DockerImagePublisher.buildRemoteRef(cfg));
    }

    // -------------------------------------------------------------------------
    // Rotulos
    // -------------------------------------------------------------------------

    @Test
    void rotulosEntramNoBuildEOContextoContinuaPorUltimo(@TempDir Path projectDir) throws IOException {
        Files.writeString(projectDir.resolve("Dockerfile"), "FROM scratch\n");
        VulcanForgeConfig cfg = VulcanForgeConfig.builder()
                .target(RegistryTarget.NEXUS)
                .dockerRegistry("nexus.example.com:8083")
                .imageName("meu-app").tag("1.0.0").dockerfilePath("Dockerfile")
                .labels(java.util.Map.of("time", "pagamentos"))
                .build();
        RecordingRunner runner = new RecordingRunner();
        new DockerImagePublisher(runner, ForgeLogger.CONSOLE).publish(cfg, null, projectDir.toFile());

        List<String> build = runner.commands.get(0);

        // O par vem como DOIS argumentos, e nao como uma string montada: sem shell no meio,
        // um valor com espaco continua sendo um valor so.
        int i = build.indexOf("--label");
        assertTrue(i > 0, "nenhum --label no build: " + build);
        assertTrue(build.contains("time=pagamentos"), "rotulo explicito ausente: " + build);

        // O automatico da OCI convive com o explicito.
        assertTrue(build.contains("org.opencontainers.image.version=1.0.0"),
                "rotulo automatico ausente: " + build);

        // ⚠️ O CONTEXTO TEM QUE SER O ULTIMO. Se um --label for acrescentado depois dele, o
        // docker interpreta o caminho como valor de flag e o build falha com uma mensagem
        // sobre argumento faltando -- que nao aponta para a causa.
        assertEquals(projectDir.toFile().getAbsolutePath(), build.get(build.size() - 1));
    }

    @Test
    void semRotulosOBuildNaoGanhaFlagNenhuma(@TempDir Path projectDir) throws IOException {
        Files.writeString(projectDir.resolve("Dockerfile"), "FROM scratch\n");
        VulcanForgeConfig cfg = VulcanForgeConfig.builder()
                .target(RegistryTarget.NEXUS)
                .dockerRegistry("nexus.example.com:8083")
                .imageName("meu-app").tag("1.0.0").dockerfilePath("Dockerfile")
                .ociLabels(false)
                .build();
        RecordingRunner runner = new RecordingRunner();
        new DockerImagePublisher(runner, ForgeLogger.CONSOLE).publish(cfg, null, projectDir.toFile());

        List<String> build = runner.commands.get(0);
        assertFalse(build.contains("--label"), "nao deveria haver rotulo: " + build);
        assertEquals(6, build.size(), "build -f <df> -t <ref> <contexto>: " + build);
    }
}
