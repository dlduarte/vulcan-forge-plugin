package io.github.dlduarte.config;

import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class EnvConfigSourceTest {

    // -------------------------------------------------------------------------------------
    // A regra de nome
    // -------------------------------------------------------------------------------------
    // Ela e um contrato publico: esta escrita no README e num gradle.properties de CI. Mudar
    // qualquer um destes nomes quebra pipeline de terceiro em silencio -- o valor simplesmente
    // deixa de ser encontrado, e o erro que aparece e um 401 do registry.

    @Test
    void mapeiaChaveSimples() {
        assertEquals("VULCANFORGE_TARGET", EnvConfigSource.envName("target"));
        assertEquals("VULCANFORGE_NAMESPACE", EnvConfigSource.envName("namespace"));
    }

    @Test
    void camelCaseViraSnakeCase() {
        assertEquals("VULCANFORGE_DOCKERFILE_PATH", EnvConfigSource.envName("dockerfilePath"));
        assertEquals("VULCANFORGE_IMAGE_NAME", EnvConfigSource.envName("imageName"));
        assertEquals("VULCANFORGE_REMOVE_LOCAL_IMAGE", EnvConfigSource.envName("removeLocalImage"));
        assertEquals("VULCANFORGE_SKIP_BUILD", EnvConfigSource.envName("skipBuild"));
    }

    @Test
    void pontoViraUnderscore() {
        assertEquals("VULCANFORGE_NEXUS_DOCKER_REGISTRY",
                EnvConfigSource.envName("nexus.dockerRegistry"));
        assertEquals("VULCANFORGE_NEXUS_SERVER_ID", EnvConfigSource.envName("nexus.serverId"));
        assertEquals("VULCANFORGE_NEXUS_MAVEN_URL", EnvConfigSource.envName("nexus.mavenUrl"));
        assertEquals("VULCANFORGE_GITHUB_NAMESPACE", EnvConfigSource.envName("github.namespace"));
    }

    @Test
    void credencialSegueAMesmaRegra() {
        assertEquals("VULCANFORGE_NEXUS_USERNAME", EnvConfigSource.envName("nexus.username"));
        assertEquals("VULCANFORGE_NEXUS_PASSWORD", EnvConfigSource.envName("nexus.password"));
        // serverId com hifen tambem e um nome valido de server
        assertEquals("VULCANFORGE_NEXUS_DOCKER_USERNAME",
                EnvConfigSource.envName("nexus-docker.username"));
    }

    @Test
    void naoDobraOSeparador() {
        // `nexus.Docker` nao e um caso real, mas se fosse nao pode virar NEXUS__DOCKER:
        // duas variaveis com nomes quase iguais e o tipo de coisa que ninguem depura.
        assertEquals("VULCANFORGE_NEXUS_DOCKER", EnvConfigSource.envName("nexus.Docker"));
    }

    // -------------------------------------------------------------------------------------
    // Leitura
    // -------------------------------------------------------------------------------------

    @Test
    void leValorDoAmbiente() {
        EnvConfigSource env = new EnvConfigSource(
                Map.of("VULCANFORGE_NEXUS_DOCKER_REGISTRY", "nexus.example.com:8083"));
        assertEquals("nexus.example.com:8083", env.get("nexus.dockerRegistry"));
    }

    @Test
    void valorEmBrancoContaComoAusente() {
        // Variavel exportada vazia e o caso comum de "o cofre nao devolveu nada". Tratar
        // como ausente faz o proximo passo reclamar do que falta, em vez de tentar
        // autenticar com string vazia e levar um 401 que nao explica nada.
        EnvConfigSource env = new EnvConfigSource(Map.of("VULCANFORGE_NEXUS_USERNAME", "   "));
        assertNull(env.get("nexus.username"));
    }

    @Test
    void aparaEspacos() {
        EnvConfigSource env = new EnvConfigSource(Map.of("VULCANFORGE_TAG", "  1.4.1  "));
        assertEquals("1.4.1", env.get("tag"));
    }

    @Test
    void ambienteVazioNaoQuebra() {
        assertNull(new EnvConfigSource(Map.of()).get("target"));
        assertNull(new EnvConfigSource((Map<String, String>) null).get("target"));
    }

    // -------------------------------------------------------------------------------------
    // Precedencia: a propriedade vence o ambiente
    // -------------------------------------------------------------------------------------

    @Test
    void naoSobrescreveOQueJaVeioDaPropriedade() {
        Map<String, String> global = new HashMap<>();
        global.put("nexus.dockerRegistry", "da-propriedade:5000");

        EnvConfigSource env = new EnvConfigSource(
                Map.of("VULCANFORGE_NEXUS_DOCKER_REGISTRY", "do-ambiente:5000"));

        Map<String, String> merged = env.mergeInto(global, java.util.List.of("nexus.dockerRegistry"));
        assertEquals("da-propriedade:5000", merged.get("nexus.dockerRegistry"));
    }

    @Test
    void preencheOQueFaltava() {
        Map<String, String> global = new HashMap<>();
        global.put("target", "nexus");

        EnvConfigSource env = new EnvConfigSource(
                Map.of("VULCANFORGE_NEXUS_DOCKER_REGISTRY", "do-ambiente:5000"));

        Map<String, String> merged = env.mergeInto(global,
                java.util.List.of("target", "nexus.dockerRegistry"));
        assertEquals("nexus", merged.get("target"));
        assertEquals("do-ambiente:5000", merged.get("nexus.dockerRegistry"));
    }

    @Test
    void naoModificaOMapaRecebido() {
        Map<String, String> global = new HashMap<>();
        EnvConfigSource env = new EnvConfigSource(Map.of("VULCANFORGE_TAG", "9.9.9"));

        env.mergeInto(global, java.util.List.of("tag"));
        assertTrue(global.isEmpty(), "mergeInto nao pode alterar o mapa de entrada");
    }

    @Test
    void aListaDeChavesCobreOTargetEscolhido() {
        // O ambiente so pode ser varrido depois de se saber o target: as chaves por target
        // sao MONTADAS. Se esta lista deixar de cobrir alguma, o valor correspondente para de
        // ser lido do ambiente sem nada reclamar.
        java.util.List<String> nexus = ConfigResolver.configKeys(RegistryTarget.NEXUS);
        assertTrue(nexus.contains("nexus.dockerRegistry"));
        assertTrue(nexus.contains("nexus.serverId"));
        assertTrue(nexus.contains("skipBuild"));

        java.util.List<String> github = ConfigResolver.configKeys(RegistryTarget.GITHUB_PACKAGES);
        assertTrue(github.contains("github.namespace"));
        assertTrue(github.stream().noneMatch(k -> k.startsWith("nexus.")));
    }
}
