package io.github.dlduarte.docker;

import io.github.dlduarte.ForgeException;
import io.github.dlduarte.ForgeLogger;
import io.github.dlduarte.config.RegistryTarget;
import io.github.dlduarte.config.VulcanForgeConfig;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ImageLabelsTest {

    /** Logger falso: registra os avisos, para o teste poder afirmar que houve um. */
    static class RecordingLogger implements ForgeLogger {
        final List<String> warns = new ArrayList<>();

        @Override
        public void info(String msg) {
        }

        @Override
        public void warn(String msg) {
            warns.add(msg);
        }

        @Override
        public void error(String msg) {
        }
    }

    private VulcanForgeConfig.Builder base() {
        return VulcanForgeConfig.builder()
                .target(RegistryTarget.NEXUS)
                .dockerRegistry("nexus.example.com:8083")
                .namespace("meu-time")
                .imageName("meu-app")
                .tag("1.4.2")
                .dockerfilePath("Dockerfile")
                .serverId("nexus-docker");
    }

    // -------------------------------------------------------------------------
    // Os rotulos automaticos
    // -------------------------------------------------------------------------

    @Test
    void escreveOsRotulosDaOciSemConfiguracaoNenhuma() {
        Map<String, String> out = ImageLabels.resolve(
                base().build(),
                Map.of("CI_COMMIT_SHA", "abc123def456"),
                null);

        assertEquals("1.4.2", out.get(ImageLabels.OCI_VERSION), "a versao e a tag da imagem");
        assertEquals("abc123def456", out.get(ImageLabels.OCI_REVISION));
        assertTrue(out.get(ImageLabels.OCI_CREATED).endsWith("Z"),
                "created e ISO-8601 em UTC, terminado em Z: " + out.get(ImageLabels.OCI_CREATED));
    }

    @Test
    void oCommitVemDoGitHubActionsTambem() {
        Map<String, String> out = ImageLabels.resolve(
                base().build(), Map.of("GITHUB_SHA", "deadbeef"), null);
        assertEquals("deadbeef", out.get(ImageLabels.OCI_REVISION));
    }

    @Test
    void aVariavelExplicitaVenceADoCi() {
        // Quem exporta VULCANFORGE_REVISION esta corrigindo o que o CI diz -- e a unica
        // saida de quem constroi fora do pipeline, ou reconstroi um commit antigo.
        Map<String, String> env = new LinkedHashMap<>();
        env.put("CI_COMMIT_SHA", "do-ci");
        env.put("VULCANFORGE_REVISION", "explicito");

        Map<String, String> out = ImageLabels.resolve(base().build(), env, null);
        assertEquals("explicito", out.get(ImageLabels.OCI_REVISION));
    }

    @Test
    void semCommitDescobertoAvisaEPublicaAssimMesmo() {
        // O plugin roda na maquina de quem desenvolve. Falhar ali transformaria um metadado
        // em obstaculo -- mas ficar em silencio e como se descobre tarde demais.
        RecordingLogger log = new RecordingLogger();
        Map<String, String> out = ImageLabels.resolve(base().build(), Map.of(), log);

        assertNull(out.get(ImageLabels.OCI_REVISION), "sem commit, sem rotulo -- nao um valor falso");
        assertEquals(1, log.warns.size(), "tem que avisar");
        assertTrue(log.warns.get(0).contains("VULCANFORGE_REVISION"),
                "o aviso tem que dizer o que foi procurado: " + log.warns.get(0));
    }

    @Test
    void aUrlDeOrigemEMontadaNoGitHubActions() {
        Map<String, String> env = Map.of(
                "GITHUB_SERVER_URL", "https://github.com",
                "GITHUB_REPOSITORY", "dlduarte/vulcan-forge-plugin");
        Map<String, String> out = ImageLabels.resolve(base().build(), env, null);
        assertEquals("https://github.com/dlduarte/vulcan-forge-plugin", out.get(ImageLabels.OCI_SOURCE));
    }

    @Test
    void aUrlDoGitLabVemPronta() {
        Map<String, String> out = ImageLabels.resolve(
                base().build(), Map.of("CI_PROJECT_URL", "http://gitlab.local/time/app"), null);
        assertEquals("http://gitlab.local/time/app", out.get(ImageLabels.OCI_SOURCE));
    }

    // -------------------------------------------------------------------------
    // Explicitos x automaticos
    // -------------------------------------------------------------------------

    @Test
    void oRotuloExplicitoVenceOAutomatico() {
        Map<String, String> out = ImageLabels.resolve(
                base().labels(Map.of(ImageLabels.OCI_VERSION, "na-marra")).build(),
                Map.of("CI_COMMIT_SHA", "abc"),
                null);
        assertEquals("na-marra", out.get(ImageLabels.OCI_VERSION));
        assertEquals("abc", out.get(ImageLabels.OCI_REVISION), "os outros continuam automaticos");
    }

    @Test
    void desligarOciLabelsDeixaSoOsExplicitos() {
        Map<String, String> out = ImageLabels.resolve(
                base().ociLabels(false).labels(Map.of("time", "pagamentos")).build(),
                Map.of("CI_COMMIT_SHA", "abc"),
                null);
        assertEquals(Map.of("time", "pagamentos"), out);
        assertFalse(out.containsKey(ImageLabels.OCI_REVISION));
    }

    @Test
    void semNadaConfiguradoEComOciDesligadoNaoHaRotulo() {
        assertTrue(ImageLabels.resolve(base().ociLabels(false).build(), Map.of(), null).isEmpty());
    }

    // -------------------------------------------------------------------------
    // A forma de string
    // -------------------------------------------------------------------------

    @Test
    void parseLePares() {
        Map<String, String> out = ImageLabels.parse("time=pagamentos, tier=api");
        assertEquals(2, out.size());
        assertEquals("pagamentos", out.get("time"));
        assertEquals("api", out.get("tier"));
    }

    @Test
    void oValorPodeConterIgual() {
        // A divisao e no PRIMEIRO `=`. Sem isto, uma URL com query no valor viraria lixo.
        assertEquals("a=b=c", ImageLabels.parse("chave=a=b=c").get("chave"));
    }

    @Test
    void parseAceitaQuebraDeLinha() {
        assertEquals(2, ImageLabels.parse("a=1\nb=2").size());
    }

    @Test
    void parseDeTextoVazioNaoQuebra() {
        assertTrue(ImageLabels.parse(null).isEmpty());
        assertTrue(ImageLabels.parse("   ").isEmpty());
    }

    @Test
    void parRecusadoSemIgual() {
        // Ignorar em silencio produziria uma imagem sem o rotulo que alguem acha que
        // configurou -- e nada no log diria isso.
        ForgeException e = assertThrows(ForgeException.class, () -> ImageLabels.parse("time=x, tier"));
        assertTrue(e.getMessage().contains("tier"), "a mensagem tem que citar o par invalido");
    }
}
