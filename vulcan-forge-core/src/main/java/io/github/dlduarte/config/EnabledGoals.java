package io.github.dlduarte.config;

import io.github.dlduarte.ForgeException;

import java.util.Arrays;
import java.util.Collection;
import java.util.EnumSet;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Goals do Vulcan Forge que o <b>projeto</b> declara como habilitados.
 *
 * <p>A declaracao e <b>obrigatoria</b> e vive no projeto, nunca na config global: e ela
 * que diz o que aquele projeto tem permissao de publicar. Um goal fora da lista se recusa
 * a rodar, evitando que um projeto que so distribui o jar via Maven acabe gerando e
 * publicando uma imagem Docker porque alguem clicou no goal errado na IDE.
 *
 * <pre>
 * &lt;!-- pom.xml, dentro do &lt;configuration&gt; do vulcan-forge-maven-plugin --&gt;
 * &lt;enabledGoals&gt;
 *   &lt;goal&gt;maven-publish&lt;/goal&gt;
 * &lt;/enabledGoals&gt;
 *
 * // build.gradle
 * vulcanForge { enabledGoals = ['vulcanMavenPublish'] }
 * </pre>
 */
public final class EnabledGoals {

    private final Set<ForgeGoal> goals;
    private final boolean declared;
    private final BuildTool tool;

    private EnabledGoals(Set<ForgeGoal> goals, boolean declared, BuildTool tool) {
        this.goals = goals;
        this.declared = declared;
        this.tool = tool;
    }

    /**
     * Interpreta os valores declarados pelo projeto. {@code null}, lista vazia ou so
     * entradas em branco contam como <b>nao declarado</b>.
     *
     * @throws ForgeException se algum valor nao corresponder a um goal conhecido
     */
    public static EnabledGoals parse(Collection<String> raw, BuildTool tool) {
        Set<ForgeGoal> parsed = EnumSet.noneOf(ForgeGoal.class);
        if (raw != null) {
            for (String value : raw) {
                if (value != null && !value.isBlank()) {
                    parsed.add(ForgeGoal.from(value));
                }
            }
        }
        return new EnabledGoals(parsed, !parsed.isEmpty(), tool);
    }

    /** {@code true} se o projeto declarou pelo menos um goal. */
    public boolean isDeclared() {
        return declared;
    }

    public boolean isEnabled(ForgeGoal goal) {
        return goals.contains(goal);
    }

    /** Os goals habilitados, com os nomes da build tool em uso (ex.: "maven-publish"). */
    public String describe() {
        return goals.stream().map(g -> g.nameIn(tool)).collect(Collectors.joining(", "));
    }

    /**
     * Deixa passar se o goal estiver habilitado; caso contrario falha com a instrucao de
     * como declara-lo (ou de por que ele nao deveria rodar ali).
     *
     * @throws ForgeException se o goal nao estiver habilitado no projeto
     */
    public void require(ForgeGoal goal) {
        if (isEnabled(goal)) {
            return;
        }
        throw new ForgeException(declared ? notEnabledMessage(goal) : notDeclaredMessage());
    }

    private String notDeclaredMessage() {
        StringBuilder sb = new StringBuilder();
        if (tool == BuildTool.GRADLE) {
            sb.append("vulcan-forge: este projeto nao declara quais tasks do plugin estao habilitadas, ")
                    .append("entao nenhuma delas roda.\n")
                    .append("Declare no build.gradle e deixe apenas as que este projeto realmente publica:\n\n")
                    .append("  vulcanForge {\n")
                    .append("      enabledGoals = [").append(quotedAll()).append("]\n")
                    .append("  }\n");
        } else {
            sb.append("vulcan-forge: este projeto nao declara quais goals do plugin estao habilitados, ")
                    .append("entao nenhum deles roda.\n")
                    .append("Declare no pom.xml, no <configuration> do vulcan-forge-maven-plugin, ")
                    .append("e deixe apenas os que este projeto realmente publica:\n\n")
                    .append("  <enabledGoals>\n");
            for (ForgeGoal g : ForgeGoal.values()) {
                sb.append("    <goal>").append(g.mavenGoal()).append("</goal>\n");
            }
            sb.append("  </enabledGoals>\n");
        }
        return sb.append('\n').append(available()).toString();
    }

    private String quotedAll() {
        return Arrays.stream(ForgeGoal.values())
                .map(g -> "'" + g.nameIn(tool) + "'").collect(Collectors.joining(", "));
    }

    private String notEnabledMessage(ForgeGoal goal) {
        String name = goal.nameIn(tool);
        if (tool == BuildTool.GRADLE) {
            return "vulcan-forge: a task '" + name + "' (" + goal.description()
                    + ") nao esta habilitada neste projeto.\n"
                    + "O build.gradle declara enabledGoals = [" + quoted() + "].\n"
                    + "Se rodar '" + name + "' aqui for mesmo a intencao, acrescente '" + name
                    + "' ao enabledGoals.";
        }
        return "vulcan-forge: o goal '" + name + "' (" + goal.description()
                + ") nao esta habilitado neste projeto.\n"
                + "O pom.xml declara <enabledGoals>: " + describe() + ".\n"
                + "Se rodar '" + name + "' aqui for mesmo a intencao, acrescente <goal>" + name
                + "</goal> ao <enabledGoals>.";
    }

    private String quoted() {
        return goals.stream().map(g -> "'" + g.nameIn(tool) + "'").collect(Collectors.joining(", "));
    }

    private String available() {
        StringBuilder sb = new StringBuilder(tool == BuildTool.GRADLE
                ? "Tasks disponiveis:" : "Goals disponiveis:");
        for (ForgeGoal g : ForgeGoal.values()) {
            sb.append("\n  - ").append(g.nameIn(tool)).append(" : ").append(g.description());
        }
        return sb.toString();
    }
}
