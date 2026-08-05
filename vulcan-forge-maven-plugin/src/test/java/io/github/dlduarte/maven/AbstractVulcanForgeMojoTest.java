package io.github.dlduarte.maven;

import org.junit.jupiter.api.Test;

import java.util.Properties;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * Precedencia de uma chave de identidade num reator: {@code -D} da linha de comando &gt;
 * {@code <properties>} efetivas do modulo &gt; {@code <configuration>} do parent pom.
 *
 * <p>As {@code <properties>} de um modulo ja incluem o que ele herda do parent, entao a camada
 * do meio significa "o valor do parent, a menos que este modulo o sobrescreva".
 */
class AbstractVulcanForgeMojoTest {

    private static Properties props(String key, String value) {
        Properties p = new Properties();
        p.setProperty(key, value);
        return p;
    }

    @Test
    void mojoParameterIsUsedWhenNothingElseIsSet() {
        assertEquals("github",
                AbstractVulcanForgeMojo.override(null, new Properties(), "target", "github"));
    }

    @Test
    void moduleOverridesTheParentConfiguration() {
        Properties module = props("vulcanforge.namespace", "time-b");

        assertEquals("time-b",
                AbstractVulcanForgeMojo.override(null, module, "namespace", "time-a"));
    }

    @Test
    void commandLineWinsOverTheModule() {
        Properties cli = props("vulcanforge.namespace", "sandbox");
        Properties module = props("vulcanforge.namespace", "time-b");

        assertEquals("sandbox",
                AbstractVulcanForgeMojo.override(cli, module, "namespace", "time-a"));
    }

    @Test
    void blankValuesFallThrough() {
        Properties cli = props("vulcanforge.tag", "  ");
        Properties module = props("vulcanforge.tag", "");

        assertEquals("1.2.3", AbstractVulcanForgeMojo.override(cli, module, "tag", "1.2.3"));
    }

    @Test
    void unsetKeyStaysNullSoTheDefaultApplies() {
        assertNull(AbstractVulcanForgeMojo.override(new Properties(), new Properties(),
                "imageName", null));
    }
}
