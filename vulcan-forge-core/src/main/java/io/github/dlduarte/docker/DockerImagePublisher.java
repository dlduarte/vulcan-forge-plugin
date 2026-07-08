package io.github.dlduarte.docker;

import io.github.dlduarte.ForgeException;
import io.github.dlduarte.ForgeLogger;
import io.github.dlduarte.config.Credentials;
import io.github.dlduarte.config.VulcanForgeConfig;
import io.github.dlduarte.process.ProcessRunner;

import java.io.File;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;

/**
 * Orquestra a publicacao de uma imagem Docker seguindo os passos:
 *
 * <ol>
 *   <li>{@code docker build -f <dockerfile> -t <imageName>:<tag> <contexto>}</li>
 *   <li>{@code docker tag <imageName>:<tag> <ref remota>}</li>
 *   <li>{@code docker login <registry> -u <user> --password-stdin} (se ha credenciais)</li>
 *   <li>{@code docker push <ref remota>}</li>
 * </ol>
 *
 * <p>O {@code clean}/{@code build} do projeto e responsabilidade da ferramenta de
 * build hospedeira (fase do Maven / dependencia de task do Gradle) e nao e refeito aqui.
 */
public class DockerImagePublisher {

    private final ProcessRunner runner;
    private final ForgeLogger log;

    public DockerImagePublisher(ProcessRunner runner, ForgeLogger log) {
        this.runner = runner;
        this.log = log != null ? log : ForgeLogger.CONSOLE;
    }

    /**
     * Executa build, tag, login e push.
     *
     * @param cfg        configuracao resolvida
     * @param creds      credenciais (pode ser {@code null} para pular o login)
     * @param projectDir raiz do projeto (contexto do build e base do Dockerfile)
     */
    public void publish(VulcanForgeConfig cfg, Credentials creds, File projectDir) {
        validate(cfg, creds, projectDir);

        String localRef = cfg.getImageName() + ":" + cfg.getTag();
        String remoteRef = buildRemoteRef(cfg);
        File dockerfile = resolveDockerfile(cfg, projectDir);

        log.info("Publicando imagem Docker: " + localRef + " -> " + remoteRef);

        // 1. build
        runner.exec(List.of("build",
                "-f", dockerfile.getAbsolutePath(),
                "-t", localRef,
                projectDir.getAbsolutePath()), projectDir, null);

        // 2. tag
        runner.exec(List.of("tag", localRef, remoteRef), projectDir, null);

        // 3. login (opcional)
        if (creds != null && creds.isComplete()) {
            String registry = registryHost(cfg);
            List<String> loginArgs = new ArrayList<>();
            loginArgs.add("login");
            if (registry != null && !registry.isBlank()) {
                loginArgs.add(registry);
            }
            loginArgs.add("-u");
            loginArgs.add(creds.getUsername());
            loginArgs.add("--password-stdin");
            runner.exec(loginArgs, projectDir, creds.getPassword());
        } else {
            log.warn("Sem credenciais completas; pulando 'docker login'. "
                    + "O push pode falhar se o registry exigir autenticacao.");
        }

        // 4. push
        runner.exec(List.of("push", remoteRef), projectDir, null);

        log.info("Imagem publicada com sucesso: " + remoteRef);

        // 5. remocao das imagens locais (opcional; falha aqui nao invalida a publicacao)
        if (cfg.isRemoveLocalImage()) {
            removeLocalImages(projectDir, localRef, remoteRef);
        }
    }

    private void removeLocalImages(File projectDir, String localRef, String remoteRef) {
        try {
            log.info("Removendo imagens locais: " + localRef + ", " + remoteRef);
            // -f para nao falhar se houver mais de uma tag; remove ambas as tags de uma vez
            runner.exec(List.of("rmi", "-f", localRef, remoteRef), projectDir, null);
        } catch (RuntimeException e) {
            log.warn("Nao foi possivel remover as imagens locais (a publicacao foi concluida): "
                    + e.getMessage());
        }
    }

    /** Preflight: garante que o Docker esta acessivel e com o daemon ativo (fail-fast). */
    public void checkDockerAvailable() {
        int code = runner.tryExec(List.of("version"));
        if (code != 0) {
            throw new ForgeException("Docker nao esta disponivel (o comando 'docker version' retornou "
                    + code + "). Verifique se o Docker esta instalado, no PATH e com o daemon em execucao.");
        }
    }

    /** Monta {@code [registry/][namespace/]imageName:tag}. */
    public static String buildRemoteRef(VulcanForgeConfig cfg) {
        StringBuilder sb = new StringBuilder();
        String registry = registryHost(cfg);
        if (registry != null && !registry.isBlank()) {
            sb.append(registry).append('/');
        }
        if (cfg.getNamespace() != null && !cfg.getNamespace().isBlank()) {
            sb.append(cfg.getNamespace()).append('/');
        }
        sb.append(cfg.getImageName()).append(':').append(cfg.getTag());
        return sb.toString();
    }

    /** Host do registry (sem esquema http/https, que o Docker nao aceita na ref). */
    static String registryHost(VulcanForgeConfig cfg) {
        String url = cfg.getDockerRegistry();
        if (url == null || url.isBlank()) {
            return null;
        }
        url = url.trim();
        int scheme = url.indexOf("://");
        if (scheme >= 0) {
            url = url.substring(scheme + 3);
        }
        // remove eventual path/trailing slash
        int slash = url.indexOf('/');
        if (slash >= 0) {
            url = url.substring(0, slash);
        }
        return url;
    }

    private File resolveDockerfile(VulcanForgeConfig cfg, File projectDir) {
        String rel = cfg.getDockerfilePath() != null ? cfg.getDockerfilePath() : "Dockerfile";
        Path p = Paths.get(rel);
        File dockerfile = p.isAbsolute() ? p.toFile() : new File(projectDir, rel);
        if (!dockerfile.isFile()) {
            throw new ForgeException("Dockerfile nao encontrado em: " + dockerfile.getAbsolutePath()
                    + ". Coloque um Dockerfile na raiz do projeto ou configure 'dockerfilePath'.");
        }
        return dockerfile;
    }

    /**
     * Valida a configuracao e o ambiente antes de qualquer trabalho (fail-fast). Lanca
     * {@link ForgeException} com mensagem clara sobre o que falta configurar.
     */
    public void validate(VulcanForgeConfig cfg, Credentials creds, File projectDir) {
        String targetKey = cfg.getTarget() != null ? cfg.getTarget().configKey() : "<target>";

        if (cfg.getImageName() == null || cfg.getImageName().isBlank()) {
            throw new ForgeException("'imageName' nao configurado (padrao: artifactId/nome do projeto).");
        }
        if (cfg.getTag() == null || cfg.getTag().isBlank()) {
            throw new ForgeException("'tag' nao configurado (padrao: version do projeto).");
        }
        if (cfg.getDockerRegistry() == null || cfg.getDockerRegistry().isBlank()) {
            throw new ForgeException("Registry Docker nao configurado para o target '" + targetKey
                    + "'. Defina 'vulcanforge." + targetKey + ".dockerRegistry' no settings.xml/gradle.properties.");
        }
        if (projectDir == null || !projectDir.isDirectory()) {
            throw new ForgeException("Diretorio do projeto invalido: " + projectDir);
        }
        // Dockerfile precisa existir.
        resolveDockerfile(cfg, projectDir);

        // Credenciais: se ha serverId, as credenciais precisam estar completas.
        if (cfg.getServerId() == null || cfg.getServerId().isBlank()) {
            log.warn("'serverId' nao configurado para o target '" + targetKey
                    + "'; o push sera feito sem 'docker login'.");
        } else if (creds == null || !creds.isComplete()) {
            throw new ForgeException("Credenciais ausentes/incompletas para o serverId '"
                    + cfg.getServerId() + "'. Configure usuario e senha no settings.xml (<server>) "
                    + "ou no gradle.properties (vulcanforge." + cfg.getServerId() + ".username/password).");
        }
    }
}
