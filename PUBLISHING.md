# Publicação (mantenedores)

Este guia é para **publicar** o Vulcan Forge. Consumidores não precisam disto — veja o
[README](README.md).

São **dois destinos independentes**, e eles têm credenciais diferentes:

| O quê | Onde | Workflow | Seções |
|---|---|---|---|
| os três módulos **Java** (core, maven-plugin, gradle-plugin) | Maven Central | [`release.yml`](.github/workflows/release.yml) | 1 a 5 |
| a ferramenta **.NET** (`dlduarte.VulcanForge.Cli`) | NuGet.org | [`release-dotnet.yml`](.github/workflows/release-dotnet.yml) | **6** |

Os dois disparam com o **mesmo gatilho**: criar uma *Release* no GitHub com a tag `vX.Y.Z`.
O lado Java usa **secrets de longa duração** (Central Portal + GPG); o lado .NET usa **Trusted
Publishing** (OIDC), e por isso **não tem chave para rotacionar**.
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

### 6.1 A política de Trusted Publishing (não há API key)

O nuget.org **não recomenda mais API key**. O caminho é **Trusted Publishing**: o GitHub emite um
token OIDC de vida curta para o job, o nuget.org valida esse token contra uma política e devolve
uma **chave temporária, válida por 1 hora**.

O que se ganha não é comodidade: **não existe segredo para vazar, nem para rotacionar**.

Em <https://www.nuget.org/account/trustedpublishing>, *Create*:

| Campo | Valor |
|---|---|
| **Policy Name** | `github-actions-vulcan-forge` (qualquer nome que ajude a identificar) |
| **Package Owner** | a sua conta do nuget.org |
| **CI/CD Provider** | `GitHub Actions` |
| **Repository Owner** | `dlduarte` |
| **Repository** | `vulcan-forge-plugin` |
| **Workflow File** | `release-dotnet.yml` |
| **Environment** | *(vazio)* — este workflow não usa *environment* do GitHub Actions |
| **Select Scopes** | ✅ **Push** → *Push new packages and package versions* |
| **Glob Patterns and Packages** | `dlduarte.*` |

> ⚠️ **O *Workflow File* é o NOME DO ARQUIVO, sem o caminho.** É `release-dotnet.yml`, e não
> `.github/workflows/release-dotnet.yml`.
>
> E **a política fica amarrada a esse nome**: renomear o arquivo quebra a publicação, e o erro
> aparece como *token recusado* — não como "arquivo renomeado".

> ⚠️ **O *Push new packages* é obrigatório aqui**, e não o *Push only new package versions*: o
> pacote `dlduarte.VulcanForge.Cli` **nunca foi publicado**, então a primeira publicação é a
> criação dele.

### 6.1.1 O único valor a cadastrar no GitHub

A ação precisa saber **qual conta** do nuget.org está publicando:

| Secret | Valor |
|---|---|
| `NUGET_USER` | o **nome de perfil** do nuget.org — o mesmo do campo *Package Owner* |

> ⚠️ **É o nome de perfil, NÃO o e-mail.** Confundir os dois é o erro mais comum aqui, e ele
> também aparece como *token recusado*, sem dizer que o problema é o usuário.
>
> Ele não é exatamente um segredo (é público na página do pacote), mas fica como secret porque é
> o que a documentação do nuget.org recomenda — e porque assim ele não precisa ser editado no
> YAML se a conta mudar.

O workflow **falha com mensagem** se ele não existir, em vez de deixar a ação reclamar de token
inválido.

### 6.2 Publicar

Criar a **Release** no GitHub (tag `vX.Y.Z`) dispara
[`release-dotnet.yml`](.github/workflows/release-dotnet.yml), que **roda os testes**, empacota e
publica.

#### ⚠️ A PRIMEIRA publicação do pacote .NET é pelo `workflow_dispatch`, e não por uma Release

O lado .NET nasceu **depois** da `v1.3.0`, e a versão é a mesma dos dois lados (ver 6.4). Isso
cria um descompasso só na primeira vez:

- a tag `v1.3.0` **já existe**, e os três jars já estão no Maven Central — de onde não se
  apaga. Recriar a Release não republicaria nada de útil;
- o pacote `dlduarte.VulcanForge.Cli 1.3.0` **nunca foi publicado**, porque o workflow dele
  ainda não existia.

Então, para a primeira vez: em *Actions → **Release (.NET / NuGet)** → Run workflow*, na
`main`. Ele publica o `1.3.0` no NuGet **sem tocar em tag nem no Maven Central**.

> **Por que não subir para `1.4.0` só para ter uma Release nova:** isso republicaria os três
> módulos Java no Central sem nenhuma mudança neles, e exigiria bump no `pom.xml` da raiz. O
> `workflow_dispatch` resolve o descompasso sem inventar uma versão.

**Da 1.4.0 em diante os dois andam juntos de novo**, pelo gatilho normal da Release.

#### ⚠️ A política pode nascer com prazo de 7 dias

O nuget.org às vezes cria a política como **temporariamente ativa por 7 dias** — tipicamente em
repositórios **privados**. Se nenhuma publicação acontecer nesse prazo, ela fica **inativa**.

O motivo é uma defesa contra *resurrection attack*: o nuget.org precisa dos **IDs** do
repositório e do dono para prender a política ao repositório original, e esses IDs só chegam no
token de uma publicação real. Sem isso, alguém poderia apagar o repositório, recriá-lo com o
mesmo nome e publicar como se nada tivesse mudado.

**Depois da primeira publicação bem-sucedida, ela vira permanente.** O prazo pode ser reiniciado
a qualquer momento, inclusive depois de expirar.

Os testes rodam aqui, e não só no CI de PR, por um motivo que não tem volta: **um pacote no
NuGet não se apaga**. A versão fica listada para sempre — o `unlist` apenas a esconde da
busca. Segundos de teste contra um erro permanente.

Publicar **localmente** exigiria uma API key de longa duração — exatamente o que o Trusted
Publishing existe para eliminar. **Não faça isso**: o OIDC só funciona de dentro do CI, que é o
ponto. Se precisar validar o empacotamento na sua máquina, pare antes do push:

```bash
cd vulcan-forge-dotnet
dotnet test -c Release
dotnet pack src/VulcanForge.Cli/VulcanForge.Cli.csproj -c Release -o ./nupkg
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
