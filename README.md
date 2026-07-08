# Vulcan Forge Plugin

Plugin de publicação de artefatos compatível com **Maven** e **Gradle**, rodando em
**Linux e Windows**. Publica em um servidor **Nexus** ou no **GitHub Packages**, em duas
formas **independentes** (comandos separados):

1. **Imagem Docker** — a partir de um `Dockerfile` na raiz do projeto integrador.
2. **Pacote Maven** — para distribuição via dependência (reusa o deploy nativo).

## Modelo de configuração

As **coordenadas do servidor** são globais e organizadas por **target** (`nexus` / `github`):
no `settings.xml` (Maven) ou no `gradle.properties` do Gradle User Home. O projeto ajusta
apenas a **identidade** do artefato (target, namespace, imageName, tag, dockerfilePath,
removeLocalImage) — nunca as coordenadas/credenciais do servidor.

```
vulcanforge.target = nexus | github          # target default (global)
vulcanforge.dockerfilePath = Dockerfile       # comum (opcional)
vulcanforge.imageName / vulcanforge.tag       # opcionais (default: artifactId / version)
vulcanforge.removeLocalImage = true           # apaga a imagem local após o push (default: true)

vulcanforge.<target>.dockerRegistry           # host do registry Docker
vulcanforge.<target>.mavenUrl                 # URL do repositório Maven
vulcanforge.<target>.namespace                # namespace / org
vulcanforge.<target>.serverId                 # id das credenciais
```

O projeto pode sobrescrever (pom `<configuration>` / DSL `vulcanForge { }`): `target`,
`namespace`, `imageName`, `tag`, `dockerfilePath`, `removeLocalImage`. As coordenadas do
servidor (`dockerRegistry`, `mavenUrl`, `serverId`) são sempre globais.

Para `github`, `dockerRegistry` assume `ghcr.io` e `mavenUrl` assume
`https://maven.pkg.github.com/<namespace>` automaticamente.

## Estrutura do monorepo

```
vulcan-forge-plugin/
├── vulcan-forge-core/            # lógica compartilhada (Java puro)
├── vulcan-forge-maven-plugin/    # plugin Maven (goals docker-publish / maven-publish)
├── vulcan-forge-gradle-plugin/   # plugin Gradle (id io.github.dlduarte.publish)
├── examples/                     # projetos de exemplo (maven-app, gradle-app)
└── examples/settings.example.xml # modelo da config global (Maven)
```

Requisitos: **Java 17+**, **Docker** instalado e no `PATH` (para publicar imagens).

## Instalação

Os artefatos são publicados no **Maven Central**, então basta referenciá-los — não é
preciso compilar o plugin. Coordenadas (versão `1.0.0`):

- `io.github.dlduarte:vulcan-forge-maven-plugin` (plugin Maven)
- `io.github.dlduarte:vulcan-forge-gradle-plugin` (plugin Gradle, id `io.github.dlduarte.publish`)

Veja como declarar em [Publicar imagem Docker](#publicar-imagem-docker).

### Build a partir do código (contribuidores)

```bash
mvn clean install
```

Constrói os três módulos e os instala no repositório local (`~/.m2`). Requer Java 17+.

## Configuração — Maven (`settings.xml`)

Config global em um `<profile>` **ativo**; credenciais em `<servers>`; `<pluginGroups>`
habilita o prefixo curto do goal. Modelo completo em
[`examples/settings.example.xml`](examples/settings.example.xml).

```xml
<settings>
  <pluginGroups>
    <pluginGroup>io.github.dlduarte</pluginGroup>
  </pluginGroups>
  <servers>
    <server>
      <id>nexus-docker</id>
      <username>USUARIO</username>
      <password>SENHA</password>
    </server>
  </servers>
  <profiles>
    <profile>
      <id>vulcan-forge</id>
      <properties>
        <vulcanforge.target>nexus</vulcanforge.target>
        <vulcanforge.nexus.dockerRegistry>nexus.example.com:8083</vulcanforge.nexus.dockerRegistry>
        <vulcanforge.nexus.mavenUrl>https://nexus.example.com/repository/maven-releases</vulcanforge.nexus.mavenUrl>
        <vulcanforge.nexus.namespace>meu-time</vulcanforge.nexus.namespace>
        <vulcanforge.nexus.serverId>nexus-docker</vulcanforge.nexus.serverId>
      </properties>
    </profile>
  </profiles>
  <activeProfiles>
    <activeProfile>vulcan-forge</activeProfile>
  </activeProfiles>
</settings>
```

## Configuração — Gradle (`gradle.properties`)

O equivalente global é o `~/.gradle/gradle.properties`, com as mesmas chaves e as
credenciais em `vulcanforge.<serverId>.username/password`:

```properties
vulcanforge.target=nexus
vulcanforge.nexus.dockerRegistry=nexus.example.com:8083
vulcanforge.nexus.mavenUrl=https://nexus.example.com/repository/maven-releases
vulcanforge.nexus.namespace=meu-time
vulcanforge.nexus.serverId=nexus-docker

vulcanforge.nexus-docker.username=USUARIO
vulcanforge.nexus-docker.password=SENHA
```

## Publicar imagem Docker

O Docker é **independente** do deploy Maven. O próprio goal já faz **clean + install**
antes do `docker build`, gerando um **único jar atualizado** em `target/` — importante
porque Dockerfiles usam `COPY target/*.jar`. Esse build roda num **processo Maven filho
com a saída capturada**: no sucesso o log de compile/testes fica omitido (só uma linha de
status); em caso de erro, o log do build é impresso para diagnóstico. Isso vale mesmo
invocando só o goal ou pela IDE. No Gradle, a task `dockerPublish` depende de
`clean` + `build`. Passos: `validação (fail-fast) → (clean+install silencioso) →
docker build → tag → login → push → remove imagem local`.

Parâmetros úteis do build prévio (Maven): `-Dvulcanforge.skipTests=true` (pula testes),
`-Dvulcanforge.buildGoals="clean package"` (troca os goals), `-Dvulcanforge.skipBuild=true`
(usa o `target/` atual, sem rebuildar).

**Maven** — declare o plugin no `pom.xml` e escolha o servidor no `<configuration><target>`
(valores: `nexus` | `github`; aliases `ghp`, `github-packages`, `ghcr`). O `<executions>`
serve só para a IDE reconhecer os parâmetros (ver nota abaixo); como o goal não tem fase
padrão, **não roda num build normal**:

```xml
<plugin>
  <groupId>io.github.dlduarte</groupId>
  <artifactId>vulcan-forge-maven-plugin</artifactId>
  <version>1.0.0</version>
  <configuration>
    <target>github</target>
  </configuration>
  <executions>
    <execution>
      <id>vulcan-forge-docker</id>
      <goals><goal>docker-publish</goal></goals>
    </execution>
  </executions>
</plugin>
```

```bash
mvn vulcan-forge:docker-publish   # ja faz clean + install + docker (um so comando)
```

> **IntelliJ marca `Element target is not allowed here`?** É só um aviso da IDE: sem um goal
> associado ao `<configuration>`, ela não sabe quais parâmetros são válidos (no Maven funciona
> normalmente). Contorne de uma destas formas: (a) adicione o `<executions>` acima; ou
> (b) use `<properties>` **no nível do projeto** (filho de `<project>`, NÃO de `<plugin>` —
> `<properties>` não é permitido dentro de `<plugin>`) e deixe o `<plugin>` sem `<configuration>`:
> ```xml
> <project>
>   ...
>   <properties>
>     <vulcanforge.target>github</vulcanforge.target>
>     <vulcanforge.namespace>minha-org</vulcanforge.namespace>
>   </properties>
>   <build><plugins>
>     <plugin>
>       <groupId>io.github.dlduarte</groupId>
>       <artifactId>vulcan-forge-maven-plugin</artifactId>
>       <version>1.0.0</version>
>     </plugin>
>   </plugins></build>
> </project>
> ```

Antes do build o goal valida a configuração e o ambiente (**fail-fast**): erra cedo, com
mensagem clara, se faltar `dockerRegistry`, credenciais do `serverId`, o `Dockerfile`, ou se
o Docker não estiver disponível. Após o push, a imagem local é removida
(`removeLocalImage`, default `true`; desative com `-Dvulcanforge.removeLocalImage=false`).

**Gradle** — aplique o plugin:

```groovy
buildscript {
    repositories { mavenCentral() }   // use mavenLocal() se estiver testando um build local
    dependencies { classpath 'io.github.dlduarte:vulcan-forge-gradle-plugin:1.0.0' }
}
plugins { id 'java' }
apply plugin: 'io.github.dlduarte.publish'

// opcional: sobrescreve a config global só neste projeto
vulcanForge {
    target = 'github'          // nexus | github
    namespace = 'minha-org'    // pode variar por projeto
    // removeLocalImage = false // (default: true)
}
```

```bash
./gradlew clean dockerPublish        # build (dep.) + docker build/tag/login/push
```

## Publicar pacote Maven

Comando **separado** do Docker; reusa o deploy nativo.

```bash
# Maven
mvn clean deploy                 # com distributionManagement próprio, ou:
mvn vulcan-forge:maven-publish   # usa a mavenUrl/serverId do target configurado

# Gradle
./gradlew vulcanMavenPublish     # configura o repositório do maven-publish e delega a 'publish'
```

## Alvos suportados

| `target`  | Imagem Docker (`dockerRegistry`) | Pacote Maven (`mavenUrl`)                       |
|-----------|----------------------------------|-------------------------------------------------|
| `nexus`   | `host:porta` (configurado)       | URL do repositório Maven (configurada)          |
| `github`  | `ghcr.io` (inferido)             | `https://maven.pkg.github.com/<namespace>` (inferido) |

Para GitHub Packages com Docker, `namespace` é o `OWNER`; a referência final fica
`ghcr.io/<OWNER>/<imageName>:<tag>`.

## Exemplos

Veja [`examples/maven-app`](examples/maven-app) e [`examples/gradle-app`](examples/gradle-app).
Para testar contra um registry local:

```bash
docker run -d -p 5000:5000 --name registry registry:2

# Maven (config global vem do settings de exemplo)
cd examples/maven-app && mvn -s ../settings.example.xml vulcan-forge:docker-publish

# Gradle (config global vem do gradle.properties do projeto de exemplo)
cd examples/gradle-app && ./gradlew clean dockerPublish

# Verificar
curl http://localhost:5000/v2/_catalog
```

> Os exemplos usam `mavenLocal()` / `-s settings.example.xml` porque rodam contra o plugin
> **compilado localmente** (`mvn clean install`). Em um projeto real, use as coordenadas
> publicadas no Maven Central e a sua config global no `~/.m2/settings.xml` /
> `~/.gradle/gradle.properties`.

## Publicação (mantenedores)

Como publicar o plugin no Maven Central (namespace, GPG, CI, secrets) está em
[PUBLISHING.md](PUBLISHING.md).

## Licença

[Apache License 2.0](LICENSE).
