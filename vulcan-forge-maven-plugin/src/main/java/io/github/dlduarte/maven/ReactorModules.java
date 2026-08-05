package io.github.dlduarte.maven;

import io.github.dlduarte.ForgeException;
import io.github.dlduarte.docker.DockerImagePublisher;
import org.apache.maven.project.MavenProject;

import java.io.File;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * Escolhe, dentro do reator, quais modulos viram imagem Docker.
 *
 * <p>Num projeto multi-modulo (parent pom com {@code <modules>}) o plugin e declarado uma
 * unica vez no parent e a selecao e automatica: entra todo modulo que produz um artefato
 * ({@code packaging != pom}) e tem um Dockerfile. Assim o parent e as libs internas ficam de
 * fora sem configuracao nenhuma, e cada servico entra so por ter o seu Dockerfile.
 *
 * <p>Um modulo pode se excluir declarando {@code <vulcanforge.skip>true</vulcanforge.skip>}
 * nas suas {@code <properties>}.
 */
final class ReactorModules {

    /** Opt-out por modulo, lido das {@code <properties>} do pom do proprio modulo. */
    static final String SKIP_PROPERTY = "vulcanforge.skip";

    private ReactorModules() {
    }

    /** Quebra uma lista separada por virgulas (ex.: o parametro {@code vulcanforge.modules}). */
    static List<String> parseList(String csv) {
        if (csv == null || csv.isBlank()) {
            return List.of();
        }
        return Arrays.stream(csv.split(","))
                .map(String::trim)
                .filter(s -> !s.isEmpty())
                .collect(Collectors.toList());
    }

    /**
     * @param reactor          projetos do reator ({@code session.getProjects()})
     * @param dockerfilePathOf caminho do Dockerfile efetivo de cada modulo (cada um pode
     *                         sobrescrever {@code vulcanforge.dockerfilePath})
     * @param explicitModules  artifactIds a considerar; vazio = todos
     * @return os modulos publicaveis, na ordem do reator
     * @throws ForgeException se a selecao ficar vazia ou citar um artifactId inexistente
     */
    static List<MavenProject> selectForDocker(List<MavenProject> reactor,
                                              Function<MavenProject, String> dockerfilePathOf,
                                              List<String> explicitModules) {
        List<MavenProject> candidates = new ArrayList<>(reactor);

        if (explicitModules != null && !explicitModules.isEmpty()) {
            Set<String> wanted = new LinkedHashSet<>(explicitModules);
            Set<String> known = reactor.stream()
                    .map(MavenProject::getArtifactId)
                    .collect(Collectors.toCollection(LinkedHashSet::new));
            List<String> unknown = wanted.stream()
                    .filter(a -> !known.contains(a))
                    .collect(Collectors.toList());
            if (!unknown.isEmpty()) {
                throw new ForgeException("'vulcanforge.modules' cita modulos que nao estao no reator: "
                        + String.join(", ", unknown)
                        + ". Modulos disponiveis: " + String.join(", ", known) + ".");
            }
            candidates = candidates.stream()
                    .filter(p -> wanted.contains(p.getArtifactId()))
                    .collect(Collectors.toList());
        }

        List<MavenProject> selected = new ArrayList<>();
        List<String> skippedByProperty = new ArrayList<>();
        List<String> skippedWithoutDockerfile = new ArrayList<>();
        for (MavenProject p : candidates) {
            if ("pom".equals(p.getPackaging())) {
                continue;
            }
            if (isSkipped(p)) {
                skippedByProperty.add(p.getArtifactId());
                continue;
            }
            String path = dockerfilePathOf.apply(p);
            if (!DockerImagePublisher.hasDockerfile(path, p.getBasedir())) {
                File expected = DockerImagePublisher.dockerfileFor(path, p.getBasedir());
                skippedWithoutDockerfile.add(p.getArtifactId() + " (" + expected + ")");
                continue;
            }
            selected.add(p);
        }

        if (selected.isEmpty()) {
            throw new ForgeException(emptySelectionMessage(skippedByProperty, skippedWithoutDockerfile));
        }
        return selected;
    }

    /** Se o modulo se excluiu com {@code vulcanforge.skip=true} nas suas {@code <properties>}. */
    static boolean isSkipped(MavenProject project) {
        return project.getProperties() != null
                && Boolean.parseBoolean(project.getProperties().getProperty(SKIP_PROPERTY));
    }

    private static String emptySelectionMessage(List<String> skippedByProperty,
                                                List<String> skippedWithoutDockerfile) {
        StringBuilder sb = new StringBuilder("Nenhum modulo do reator tem imagem Docker para publicar.");
        if (!skippedWithoutDockerfile.isEmpty()) {
            sb.append(" Sem Dockerfile: ").append(String.join(", ", skippedWithoutDockerfile)).append('.');
        }
        if (!skippedByProperty.isEmpty()) {
            sb.append(" Com ").append(SKIP_PROPERTY).append("=true: ")
                    .append(String.join(", ", skippedByProperty)).append('.');
        }
        sb.append(" Adicione um Dockerfile ao modulo que deve virar imagem,"
                + " ou ajuste 'vulcanforge.dockerfilePath'.");
        return sb.toString();
    }
}
