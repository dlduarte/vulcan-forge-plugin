# Vulcan Forge Plugin

Plugin de publicação de artefatos compatível com **Maven** e **Gradle**, rodando em
**Linux e Windows**. Publica em um servidor **Nexus** ou no **GitHub Packages**, em duas
formas **independentes** (comandos separados):

1. **Imagem Docker** — a partir de um `Dockerfile` na raiz do projeto integrador.
2. **Pacote Maven** — para distribuição via dependência (reusa o deploy nativo).

## Goals habilitados por projeto (obrigatório)

Cada projeto **declara quais goals do plugin ele pode rodar**. Um goal fora dessa lista se
recusa a executar — é o que impede que alguém clique no goal errado na IDE e um projeto que
só distribui o jar acabe gerando e publicando uma imagem Docker.

| Publicação    | Goal (Maven)     | Task (Gradle)        |
|---------------|------------------|----------------------|
| Imagem Docker | `docker-publish` | `dockerPublish`      |
| Pacote Maven  | `maven-publish`  | `vulcanMavenPublish` |

```xml
<!-- pom.xml, no <configuration> do vulcan-forge-maven-plugin -->
<enabledGoals>
  <goal>maven-publish</goal>
</enabledGoals>
```

```groovy
// build.gradle
vulcanForge {
    enabledGoals = ['vulcanMavenPublish']
}
```

Os nomes são intercambiáveis (`docker-publish`, `dockerPublish` ou `docker`; `maven-publish`,
`vulcanMavenPublish` ou `maven`), sem diferenciar maiúsculas/hífens — a mesma lista serve
para os dois mundos.

Sem a declaração, **nenhum** goal roda — inclusive em projetos que já usavam o plugin, que
precisam acrescentá-la. A falha explica o que declarar e o que cada goal faz.
Se o goal existe mas não está habilitado, a mensagem mostra o que o projeto declarou e como
liberá-lo, caso seja intencional. Nos dois casos a checagem é a **primeira** coisa que roda:
nada é buildado, empacotado ou enviado antes dela.

No **Gradle** a proteção também é visual: as tasks não declaradas saem do grupo `vulcan forge`
e somem do `./gradlew tasks` e do painel do Gradle na IDE. Elas também não ganham as
dependências (`build`, `publish`), então nem isso roda se alguém as invocar pelo nome.
No **Maven**, a IDE lista os goals a partir do descritor do plugin, então eles continuam
visíveis — a proteção é a falha imediata.

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
servidor (`dockerRegistry`, `mavenUrl`, `serverId`) são sempre globais. O `enabledGoals` é
por-projeto por definição — não faz sentido (nem tem efeito) declará-lo globalmente.

Para `github`, `dockerRegistry` assume `ghcr.io` e `mavenUrl` assume
`https://maven.pkg.github.com/<namespace>` automaticamente.

## Estrutura do monorepo

```
vulcan-forge-plugin/
├── vulcan-forge-core/            # lógica compartilhada (Java puro)
├── vulcan-forge-maven-plugin/    # plugin Maven (goals docker-publish / maven-publish)
├── vulcan-forge-gradle-plugin/   # plugin Gradle (id io.github.dlduarte.publish)
├── examples/                     # projetos de exemplo (single-module e multi-módulo)
└── examples/settings.example.xml # modelo da config global (Maven)
```

Requisitos: **Java 17+**, **Docker** instalado e no `PATH` (para publicar imagens).

## Instalação

Os artefatos são publicados no **Maven Central**, então basta referenciá-los — não é
preciso compilar o plugin. Coordenadas (versão `1.2.0`):

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

Num projeto de vários módulos, declare o plugin só no parent — veja
[Projeto multi-módulo (reator)](#projeto-multi-módulo-reator).

**Maven** — declare o plugin no `pom.xml` e escolha o servidor no `<configuration><target>`
(valores: `nexus` | `github`; aliases `ghp`, `github-packages`, `ghcr`). O `<executions>`
serve só para a IDE reconhecer os parâmetros (ver nota abaixo); como o goal não tem fase
padrão, **não roda num build normal**:

```xml
<plugin>
  <groupId>io.github.dlduarte</groupId>
  <artifactId>vulcan-forge-maven-plugin</artifactId>
  <version>1.2.0</version>
  <configuration>
    <enabledGoals>
      <goal>docker-publish</goal>
    </enabledGoals>
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
>     <vulcanforge.enabledGoals>docker-publish</vulcanforge.enabledGoals>
>     <vulcanforge.target>github</vulcanforge.target>
>     <vulcanforge.namespace>minha-org</vulcanforge.namespace>
>   </properties>
>   <build><plugins>
>     <plugin>
>       <groupId>io.github.dlduarte</groupId>
>       <artifactId>vulcan-forge-maven-plugin</artifactId>
>       <version>1.2.0</version>
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
    dependencies { classpath 'io.github.dlduarte:vulcan-forge-gradle-plugin:1.2.0' }
}
plugins { id 'java' }
apply plugin: 'io.github.dlduarte.publish'

vulcanForge {
    enabledGoals = ['dockerPublish']   // obrigatório: o que este projeto pode publicar

    // opcional: sobrescreve a config global só neste projeto
    target = 'github'          // nexus | github
    namespace = 'minha-org'    // pode variar por projeto
    // removeLocalImage = false // (default: true)
}
```

```bash
./gradlew clean dockerPublish        # build (dep.) + docker build/tag/login/push
```

## Publicar pacote Maven

Comando **separado** do Docker; reusa o deploy nativo. O goal `maven-publish` **empacota o
projeto sozinho**: roda `clean deploy` num processo Maven filho (mesmo padrão do
`docker-publish`), garantindo um `target/` limpo antes de publicar. Não é preciso rodar
`clean`/`package` antes, e — como não tem fase padrão — ele nunca roda num build normal.
A saída do build filho fica omitida no sucesso e é impressa em caso de erro.

> **A URL do repositório (`mavenUrl`) precisa ser `https` se o Nexus redirecionar (301).**
> O Maven segue redirect em GET (downloads), mas **não** em PUT (deploy), então um
> `http` que redireciona para `https` falha no upload com `301 Moved Permanently`.

Exige `maven-publish` / `vulcanMavenPublish` em [`enabledGoals`](#goals-habilitados-por-projeto-obrigatório).

Parâmetro útil (Maven): `-Dvulcanforge.skipTests=true` pula os testes na publicação.

Num reator, o `clean deploy` do filho publica o parent e todos os módulos de uma vez — veja
[Projeto multi-módulo (reator)](#projeto-multi-módulo-reator).

```bash
# Maven
mvn clean deploy                 # com distributionManagement próprio, ou:
mvn vulcan-forge:maven-publish   # usa a mavenUrl/serverId do target configurado (já faz clean)

# Gradle
./gradlew vulcanMavenPublish     # configura o repositório do maven-publish e delega a 'publish'
```

## Projeto multi-módulo (reator)

Em um monorepo no estilo Spring — um parent pom agregando serviços e bibliotecas internas —
o plugin é declarado **uma única vez, no parent**. Os dois goals são **agregadores**: rodam
uma só vez, no topo do reator, e fazem **um único build filho** que cobre todos os módulos
(em vez de um build por módulo).

```
reactor-app/          # packaging pom — o plugin é declarado aqui
├── commons/          # lib interna, sem Dockerfile
├── service-a/        # Dockerfile → vira imagem
└── service-b/        # Dockerfile → vira imagem
```

```bash
mvn vulcan-forge:docker-publish    # 1 'clean install' do reator + imagem de service-a e service-b
mvn vulcan-forge:maven-publish     # 1 'clean deploy' do reator inteiro (parent + módulos)
```

**Quais módulos viram imagem** é automático: todo módulo com `packaging != pom`, com um
`Dockerfile`, **e** que declare `docker-publish` no [`<enabledGoals>`](#goals-habilitados-por-projeto-obrigatório).
O parent e as libs internas ficam de fora sem configuração nenhuma. Cada módulo usa o próprio
`artifactId`/`version` como `imageName`/`tag`.

O `<enabledGoals>` declarado no parent vale para todos os módulos, e cada módulo pode
restringir o que herdou:

```xml
<!-- parent pom, no <configuration> do plugin: o que o reator pode publicar -->
<enabledGoals>
  <goal>docker-publish</goal>
  <goal>maven-publish</goal>
</enabledGoals>
```

```xml
<!-- commons/pom.xml: esta lib distribui o jar, mas nunca vira imagem -->
<properties>
  <vulcanforge.enabledGoals>maven-publish</vulcanforge.enabledGoals>
</properties>
```

No `maven-publish` a garantia é verificada **antes** do fork: como o `clean deploy` publica o
reator inteiro de uma vez, todo módulo que seria publicado precisa declarar `maven-publish`.
Se algum não declarar, o goal falha listando quais — e a saída é declarar o goal nele ou
tirá-lo do deploy com `<maven.deploy.skip>true</maven.deploy.skip>`. Nada é publicado antes
dessa checagem.

Selecionar um subconjunto:

```bash
mvn vulcan-forge:docker-publish -pl service-a              # -pl da linha de comando
mvn vulcan-forge:docker-publish -Dvulcanforge.modules=service-a,service-b
```

A seleção (`-pl`/`-am`) é reproduzida no build filho. No `docker-publish` o `-am` é sempre
aplicado, para que as dependências irmãs do módulo selecionado compilem.

**Sobrescrever ou excluir por módulo** — nas `<properties>` do pom do próprio módulo, que
têm precedência sobre a `<configuration>` herdada do parent (e perdem para um `-D` da linha
de comando):

```xml
<properties>
  <vulcanforge.imageName>app-b</vulcanforge.imageName>  <!-- também: namespace, tag, dockerfilePath -->
  <vulcanforge.enabledGoals>maven-publish</vulcanforge.enabledGoals>  <!-- restringe o herdado -->
  <vulcanforge.skip>true</vulcanforge.skip>             <!-- fora do docker-publish -->
  <maven.deploy.skip>true</maven.deploy.skip>           <!-- fora do maven-publish (nativo do Maven) -->
</properties>
```

> **Não defina `imageName` ou `tag` no parent nem no `settings.xml` de um reator** — o valor
> valeria para todos os módulos e um sobrescreveria a imagem do outro. O goal detecta isso e
> falha cedo, apontando os módulos em conflito.

**Gradle** — aplique o plugin **só no root**; ele se propaga para os subprojects e as tasks
do root viram agregadoras:

```groovy
// build.gradle do root
apply plugin: 'io.github.dlduarte.publish'

subprojects {
    apply plugin: 'java'
    vulcanForge { enabledGoals = ['dockerPublish', 'vulcanMavenPublish'] }
}
```

```bash
./gradlew dockerPublish              # imagem de cada subproject que tenha Dockerfile
./gradlew :service-a:dockerPublish   # só um subproject
```

Por subproject, a DSL sobrescreve o que veio do root — inclusive o `enabledGoals` e o opt-out
`vulcanForge { skip = true }`. As tasks do root só disparam as dos subprojects que declararam
aquele goal, então uma lib que só publica jar não entra no `dockerPublish` do root nem quebra
o build. O root em si não precisa declarar nada: sem declaração, as tasks dele apenas agregam.

## Alvos suportados

| `target`  | Imagem Docker (`dockerRegistry`) | Pacote Maven (`mavenUrl`)                       |
|-----------|----------------------------------|-------------------------------------------------|
| `nexus`   | `host:porta` (configurado)       | URL do repositório Maven (configurada)          |
| `github`  | `ghcr.io` (inferido)             | `https://maven.pkg.github.com/<namespace>` (inferido) |

Para GitHub Packages com Docker, `namespace` é o `OWNER`; a referência final fica
`ghcr.io/<OWNER>/<imageName>:<tag>`.

## Exemplos

| Exemplo | O que mostra |
|---------|--------------|
| [`examples/maven-app`](examples/maven-app) | projeto Maven de um módulo só |
| [`examples/gradle-app`](examples/gradle-app) | projeto Gradle de um módulo só |
| [`examples/maven-reactor-app`](examples/maven-reactor-app) | reator com parent + 2 serviços + 1 lib |
| [`examples/gradle-multi-project`](examples/gradle-multi-project) | build multi-projeto equivalente |

Para testar contra um registry local:

```bash
docker run -d -p 5000:5000 --name registry registry:2

# Maven (config global vem do settings de exemplo)
cd examples/maven-app && mvn -s ../settings.example.xml vulcan-forge:docker-publish

# Maven multi-módulo — publica service-a e service-b, ignora o parent e commons
cd examples/maven-reactor-app && mvn -s ../settings.example.xml vulcan-forge:docker-publish

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
