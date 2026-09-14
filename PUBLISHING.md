# Publicação (mantenedores)

Este guia é para **publicar** o Vulcan Forge. Consumidores não precisam disto — veja o
[README](README.md).

São **dois destinos independentes**, e eles têm credenciais diferentes:

| O quê | Onde | Workflow | Seções |
|---|---|---|---|
| os três módulos **Java** (core, maven-plugin, gradle-plugin) | Maven Central | [`release.yml`](.github/workflows/release.yml) | 1 a 5 |
| a ferramenta **.NET** (`dlduarte.VulcanForge.Cli`) | NuGet.org | [`release-dotnet.yml`](.github/workflows/release-dotnet.yml) | **6** |

Os dois disparam com o **mesmo gatilho**: criar uma *Release* no GitHub com a tag `vX.Y.Z`.
São workflows separados de propósito — publicam em servidores diferentes, e um pode falhar sem
o outro. Juntos, uma falha de GPG impediria a publicação do pacote .NET; e o contrário seria
pior, porque um `dotnet nuget push` vermelho deixaria a Release parecendo que nada foi
publicado, com os três jars já no Central (**de onde não se apaga**).

A publicação usa o **Sonatype Central Portal** via `central-publishing-maven-plugin`,
com assinatura **GPG**, no perfil Maven `release` (não roda no build normal).

## 1. Coordenadas do projeto

O namespace **`io.github.dlduarte`** — já verificado no Central via GitHub (igual ao
`supple-tools`) — é usado no `groupId`, nos pacotes Java e no id do plugin Gradle
(`io.github.dlduarte.publish`). Coordenadas publicadas:

- `io.github.dlduarte:vulcan-forge-core`
- `io.github.dlduarte:vulcan-forge-maven-plugin`
- `io.github.dlduarte:vulcan-forge-gradle-plugin`

## 2. Conta e token do Central Portal

1. Crie conta em https://central.sonatype.com e registre/verifique o namespace (groupId).
2. Gere um **token de publicação** (User Token): guarde o *username* e *password* do token.

## 3. Chave GPG

O Central exige artefatos assinados.

```bash
gpg --gen-key                              # crie uma chave (guarde a passphrase)
gpg --list-keys                            # anote o KEY_ID
gpg --keyserver keyserver.ubuntu.com --send-keys KEY_ID   # publique a chave
gpg --armor --export-secret-keys KEY_ID > private-key.asc # para o CI (secret)
```

## 4a. Publicar via GitHub Actions (recomendado)

Configure os **secrets** do repositório (Settings → Secrets and variables → Actions).
São os **mesmos nomes** usados no `supple-tools`, então você pode reaproveitar os valores:

| Secret            | Valor                                             |
|-------------------|---------------------------------------------------|
| `OSSRH_USERNAME`  | username do User Token do Central Portal          |
| `OSSRH_TOKEN`     | password do User Token do Central Portal          |
| `GPG_PRIVATE_KEY` | conteúdo de `private-key.asc` (chave GPG privada) |
| `GPG_PASSPHRASE`  | passphrase da chave GPG                           |

> O nome `OSSRH_*` é histórico; o valor é o **User Token do Central Portal**
> (`central.sonatype.com` → *View Account* → *Generate User Token*).

Depois, crie uma **Release** no GitHub (tag `vX.Y.Z`). O workflow
[`.github/workflows/release.yml`](.github/workflows/release.yml) roda
`mvn -Prelease clean deploy` e publica automaticamente.

## 4b. Publicar localmente

Adicione o servidor `central` ao seu `~/.m2/settings.xml`:

```xml
<servers>
  <server>
    <id>central</id>
    <username>TOKEN_USERNAME</username>
    <password>TOKEN_PASSWORD</password>
  </server>
</servers>
```

E rode (com a chave GPG disponível no agente):

```bash
mvn -Prelease clean deploy
```

O `central-publishing-maven-plugin` está com `autoPublish=true`, então a release é publicada
sem passo manual no portal. Os três módulos (`core`, `maven-plugin`, `gradle-plugin`) vão
juntos, cada um com `-sources.jar`, `-javadoc.jar` e assinaturas `.asc`.

## 5. Após publicar (Java)

- Leva alguns minutos até sincronizar para o `repo.maven.apache.org`.
- Atualize a versão no README/exemplos se necessário e faça o bump para a próxima
  versão de desenvolvimento.

---

## 6. Publicar a ferramenta .NET

A ferramenta `dlduarte.VulcanForge.Cli` é um `dotnet tool`, e vai para o **NuGet.org** — que é
onde o `dotnet tool install` procura por padrão.

### 6.1 O secret

**É o único passo que falta**, e ele não pode ser feito por automação:

1. em <https://www.nuget.org/account/apikeys>, crie uma **API key** com escopo **Push**,
   limitada ao pacote `dlduarte.VulcanForge.Cli` (ou ao *glob* `dlduarte.*`);
2. em *Settings → Secrets and variables → Actions*, cadastre:

| Secret | Valor |
|---|---|
| `NUGET_API_KEY` | a API key do NuGet.org |

> ⚠️ **O workflow FALHA, com mensagem, se o secret não existir** — ele não tenta publicar
> anônimo. Sem a guarda, o `dotnet nuget push` levaria 401 do NuGet.org, e um 401 não fala de
> secret faltando.

### 6.2 Publicar

Criar a **Release** no GitHub (tag `vX.Y.Z`) dispara
[`release-dotnet.yml`](.github/workflows/release-dotnet.yml), que **roda os testes**, empacota e
publica.

Os testes rodam aqui, e não só no CI de PR, por um motivo que não tem volta: **um pacote no
NuGet não se apaga**. A versão fica listada para sempre — o `unlist` apenas a esconde da
busca. Segundos de teste contra um erro permanente.

Para publicar **localmente** (não recomendado — a chave fica no seu histórico de shell):

```bash
cd vulcan-forge-dotnet
dotnet test -c Release
dotnet pack src/VulcanForge.Cli/VulcanForge.Cli.csproj -c Release -o ./nupkg
dotnet nuget push ./nupkg/*.nupkg --source https://api.nuget.org/v3/index.json --api-key <CHAVE> --skip-duplicate
```

### 6.3 ⚠️ O consumidor precisa ALCANÇAR o feed

Isto não é detalhe de publicação, é o que decide se a esteira funciona: o job de CI que usa a
ferramenta roda `dotnet tool restore`, e **o runner precisa chegar ao NuGet.org**.

Numa rede fechada isso pode não valer. Se não valer, o caminho é espelhar o pacote num feed
interno (um repositório **nuget** do Nexus, por exemplo) e apontar o `NuGet.config` do projeto
consumidor para ele. **Não confira isso pela sua máquina** — teste de dentro de um container na
rede do runner, que é o que a organização já faz para o Nexus e o Portainer.

> **GitHub Packages foi considerado e recusado** como destino: ele exige autenticação **até
> para leitura**, então todo projeto consumidor precisaria de um token do GitHub só para
> restaurar uma ferramenta de build. NuGet.org é público na leitura.

### 6.4 A versão é a mesma dos dois lados

`vulcan-forge-dotnet/Directory.Build.props` declara a mesma `<Version>` do `pom.xml` da raiz, e
isso é deliberado: `vulcan-forge` é um produto só, com três hospedeiros. Duas linhas de versão
independentes obrigariam quem lê uma esteira a descobrir qual número se aplica a qual mundo.

O preço aceito: uma correção que só toca o lado Java sobe a versão daqui também.
