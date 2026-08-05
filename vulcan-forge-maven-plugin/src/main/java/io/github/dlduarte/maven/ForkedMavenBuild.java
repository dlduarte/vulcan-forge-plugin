package io.github.dlduarte.maven;

import org.apache.maven.execution.MavenExecutionRequest;
import org.apache.maven.execution.MavenSession;
import org.apache.maven.plugin.MojoExecutionException;
import org.apache.maven.plugin.logging.Log;
import org.apache.maven.shared.invoker.DefaultInvocationRequest;
import org.apache.maven.shared.invoker.DefaultInvoker;
import org.apache.maven.shared.invoker.InvocationOutputHandler;
import org.apache.maven.shared.invoker.InvocationRequest;
import org.apache.maven.shared.invoker.InvocationResult;
import org.apache.maven.shared.invoker.Invoker;
import org.apache.maven.shared.invoker.MavenInvocationException;

import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Properties;

/**
 * Roda um build Maven num <b>processo filho</b>, com a saida capturada: no sucesso nada e
 * impresso, na falha o log inteiro e despejado para diagnostico.
 *
 * <p>O fork acontece sempre na <b>raiz de execucao</b> do reator (o pom que o usuario passou
 * na linha de comando), nunca no pom de um modulo isolado. Num projeto multi-modulo isso faz
 * um unico build cobrir o reator inteiro, com o Maven resolvendo a ordem e as dependencias
 * entre modulos nativamente. A selecao do usuario ({@code -pl} / {@code -am}) e propagada,
 * entao {@code mvn vulcan-forge:docker-publish -pl service-a} reproduz a mesma selecao no filho.
 */
class ForkedMavenBuild {

    /**
     * Propriedade injetada no build filho. Os mojos abortam quando a veem, evitando fork
     * recursivo infinito caso alguem amarre um goal do Vulcan Forge a uma fase do ciclo de vida.
     */
    static final String FORKED_FLAG = "vulcanforge.forked";

    private final MavenSession session;
    private final Log log;

    ForkedMavenBuild(MavenSession session, Log log) {
        this.session = session;
        this.log = log;
    }

    /** Se a sessao atual ja e um build filho disparado por nos. */
    static boolean isForked(MavenSession session) {
        Properties user = session.getUserProperties();
        return user != null && Boolean.parseBoolean(user.getProperty(FORKED_FLAG));
    }

    /**
     * Monta o {@link InvocationRequest} do build filho. Separado de {@link #run} para permitir
     * teste sem executar Maven de verdade.
     *
     * @param goals            goals do build filho (ex.: {@code clean install})
     * @param extraProps       propriedades adicionais (ex.: {@code altDeploymentRepository})
     * @param alsoMakeUpstream forca {@code -am} quando ha selecao de modulos; usar quando o
     *                         filho precisa compilar as dependencias irmas dos modulos selecionados
     */
    InvocationRequest buildRequest(List<String> goals, Properties extraProps, boolean alsoMakeUpstream) {
        MavenExecutionRequest parent = session.getRequest();

        InvocationRequest request = new DefaultInvocationRequest();
        request.setPomFile(executionRootPom(parent));
        request.setBaseDirectory(executionRootDirectory(parent));
        request.setGoals(new ArrayList<>(goals));
        request.setBatchMode(true);

        // As propriedades da execucao atual valem no filho (ex.: -DskipTests, -Dspring.profiles),
        // mas as nossas tem precedencia.
        Properties props = new Properties();
        copyStringProperties(parent.getUserProperties(), props);
        if (extraProps != null) {
            copyStringProperties(extraProps, props);
        }
        props.setProperty(FORKED_FLAG, "true");
        request.setProperties(props);

        // Reproduz a selecao de modulos do usuario (-pl / -am / -amd) no reator do filho.
        List<String> selected = parent.getSelectedProjects();
        if (selected != null && !selected.isEmpty()) {
            request.setProjects(new ArrayList<>(selected));
            String make = parent.getMakeBehavior();
            boolean upstream = MavenExecutionRequest.REACTOR_MAKE_UPSTREAM.equals(make)
                    || MavenExecutionRequest.REACTOR_MAKE_BOTH.equals(make);
            boolean downstream = MavenExecutionRequest.REACTOR_MAKE_DOWNSTREAM.equals(make)
                    || MavenExecutionRequest.REACTOR_MAKE_BOTH.equals(make);
            request.setAlsoMake(upstream || alsoMakeUpstream);
            request.setAlsoMakeDependents(downstream);
        }

        // Propaga o resto do contexto da execucao atual.
        if (parent.getUserSettingsFile() != null) {
            request.setUserSettingsFile(parent.getUserSettingsFile());
        }
        if (parent.getGlobalSettingsFile() != null) {
            request.setGlobalSettingsFile(parent.getGlobalSettingsFile());
        }
        if (session.getLocalRepository() != null && session.getLocalRepository().getBasedir() != null) {
            request.setLocalRepositoryDirectory(new File(session.getLocalRepository().getBasedir()));
        }
        if (parent.getActiveProfiles() != null && !parent.getActiveProfiles().isEmpty()) {
            request.setProfiles(new ArrayList<>(parent.getActiveProfiles()));
        }
        request.setOffline(parent.isOffline());

        return request;
    }

    /**
     * Executa o build filho.
     *
     * @param label rotulo curto usado nas mensagens (ex.: {@code "build previo"})
     */
    void run(List<String> goals, Properties extraProps, boolean alsoMakeUpstream, String label)
            throws MojoExecutionException {
        InvocationRequest request = buildRequest(goals, extraProps, alsoMakeUpstream);

        // Captura stdout+stderr; so mostramos em caso de erro.
        StringBuilder captured = new StringBuilder();
        InvocationOutputHandler handler = line -> captured.append(line).append(System.lineSeparator());
        request.setOutputHandler(handler);
        request.setErrorHandler(handler);

        Invoker invoker = new DefaultInvoker();
        File mavenHome = resolveMavenHome();
        if (mavenHome != null) {
            invoker.setMavenHome(mavenHome);
        }

        InvocationResult result;
        try {
            result = invoker.execute(request);
        } catch (MavenInvocationException e) {
            dumpBuildLog(captured, label);
            throw new MojoExecutionException("Nao foi possivel executar o " + label + ". "
                    + "Verifique se o Maven esta acessivel (maven.home/M2_HOME).", e);
        }

        if (result.getExitCode() != 0) {
            dumpBuildLog(captured, label);
            throw new MojoExecutionException("O " + label + " ('" + String.join(" ", goals)
                    + "') falhou com exit code " + result.getExitCode() + ".",
                    result.getExecutionException());
        }
    }

    /** O pom passado na linha de comando (raiz do reator), nao o do modulo corrente. */
    private File executionRootPom(MavenExecutionRequest parent) {
        if (parent.getPom() != null) {
            return parent.getPom();
        }
        File root = executionRootDirectory(parent);
        return root != null ? new File(root, "pom.xml") : null;
    }

    private File executionRootDirectory(MavenExecutionRequest parent) {
        if (parent.getBaseDirectory() != null && !parent.getBaseDirectory().isBlank()) {
            return new File(parent.getBaseDirectory());
        }
        String root = session.getExecutionRootDirectory();
        return (root == null || root.isBlank()) ? null : new File(root);
    }

    private static void copyStringProperties(Properties from, Properties to) {
        if (from == null) {
            return;
        }
        for (Map.Entry<Object, Object> entry : from.entrySet()) {
            if (entry.getKey() instanceof String && entry.getValue() instanceof String) {
                to.setProperty((String) entry.getKey(), (String) entry.getValue());
            }
        }
    }

    private void dumpBuildLog(StringBuilder captured, String label) {
        if (captured.length() > 0) {
            log.error("--- saida do " + label + " ---");
            log.error(System.lineSeparator() + captured);
            log.error("--- fim da saida do " + label + " ---");
        }
    }

    private File resolveMavenHome() {
        String home = System.getProperty("maven.home");
        if (home == null || home.isBlank()) {
            home = System.getenv("MAVEN_HOME");
        }
        if (home == null || home.isBlank()) {
            home = System.getenv("M2_HOME");
        }
        return (home == null || home.isBlank()) ? null : new File(home);
    }
}
