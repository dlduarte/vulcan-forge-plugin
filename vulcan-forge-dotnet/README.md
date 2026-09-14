# Vulcan Forge — o lado .NET

Publica **imagem Docker** (e **pacote NuGet**) de um projeto .NET, gravando os **rótulos da
OCI**. É o irmão dos plugins Maven e Gradle deste repositório: mesmos rótulos, mesmos nomes de
variável, mesma trava de goals.

```bash
cd app
dotnet publish -c Release -o publish     # ⚠️ esta ferramenta NÃO compila
dotnet vulcan-forge docker-publish --tag 1.4.0
```

---

## Por que uma ferramenta de linha de comando, e não um plugin de build

No Maven e no Gradle o Vulcan Forge é um **plugin**, porque as duas ferramentas têm um ciclo de
vida de build com pontos de extensão de terceiros. **No .NET não há equivalente que sirva:**
`dotnet publish` executa alvos do MSBuild, e prender a publicação da imagem a um alvo faria a
imagem ser (re)construída junto com o código — exatamente o contrário do que uma esteira de
promoção precisa.

Uma **ferramenta local** (`dotnet tool`) tem, de graça, a propriedade que importa: a **versão
fica pinada em `.config/dotnet-tools.json`**, que é versionado e revisado — a mesma garantia que
o `<version>` do plugin no `pom.xml` dá. E ela é invocada como comando, que é o que um job de CI
faz.

---

## Instalação

Como ferramenta **local** do projeto (é a forma recomendada — ela pina a versão):

```bash
cd app
dotnet new tool-manifest          # se ainda não houver .config/dotnet-tools.json
dotnet tool install dlduarte.VulcanForge.Cli
```

Depois, em qualquer clone: `dotnet tool restore`.

> **Multi-alvo, e isso não é zelo:** o pacote traz `net8.0` e `net10.0`. A ferramenta roda
> **dentro da imagem do SDK** do job de CI, e uma imagem `sdk:8.0` traz só o runtime 8 — um
> pacote de alvo único não rodaria lá, e a falha seria na hora de publicar a imagem.

---

## Os dois goals

| Goal | O que faz |
|---|---|
| `docker-publish` | build, tag, login e push da imagem — **com os rótulos da OCI** |
| `nuget-publish` | empurra os `.nupkg` já empacotados |

### ⚠️ O projeto declara o que pode publicar

A declaração é **obrigatória** e vive em `vulcanforge.json`, no projeto:

```json
{
  "enabledGoals": ["docker-publish"],
  "imageName": "averba-senig",
  "namespace": "br-com-soften/servicos-hibridos",
  "dockerfilePath": "../infra/docker/Dockerfile",
  "contextPath": ".",
  "dockerTarget": "aplicacao"
}
```

Um goal fora dessa lista **se recusa a rodar**, e essa é a **primeira coisa conferida** — nada é
construído ou enviado antes dela. O que isso impede, concretamente: um projeto que só distribui
pacote NuGet acabar construindo e empurrando uma imagem porque alguém digitou o comando errado,
ou porque um job foi copiado de outro repositório.

---

## Os rótulos, e por que eles não são opcionais

Uma imagem publicada sem rótulo **não sabe dizer de qual commit ela saiu**. Enquanto está no
pipeline que a construiu, a resposta está no ambiente do job; depois que o pipeline acaba, ela
desaparece — e o `git log` não ajuda, porque ele não sabe qual commit virou imagem.

Isso deixa de ser detalhe no momento em que uma esteira **promove** imagem em vez de
reconstruí-la: para saber de onde cortar a branch de release, alguém precisa perguntar à
própria imagem.

| Rótulo | De onde vem |
|---|---|
| `org.opencontainers.image.revision` | o commit, do ambiente de CI |
| `org.opencontainers.image.version` | a `tag` |
| `org.opencontainers.image.created` | o instante do build, em UTC |
| `org.opencontainers.image.source` | a URL do repositório, do ambiente de CI |

O commit é procurado **nesta ordem**: `VULCANFORGE_REVISION`, `CI_COMMIT_SHA`, `GITHUB_SHA`,
`BUILD_VCS_NUMBER`, `GIT_COMMIT` — **a mesma do lado Java**, para que uma esteira que lê o
rótulo de volta não dependa de qual mundo construiu a imagem.

**Não achou?** A imagem é publicada assim mesmo (a ferramenta também roda na máquina de quem
desenvolve), mas o log **avisa**. Uma esteira que dependa do rótulo deve **conferir que ele
chegou**, e não supor.

Rótulos escritos à mão **vencem** os automáticos: quem configurou um valor disse o que queria.

---

## Configuração, e a precedência

| Onde | O quê |
|---|---|
| **linha de comando** | o mais explícito. É o que a esteira usa para a tag |
| **`vulcanforge.json`** | a declaração versionada do projeto: identidade e goals |
| **ambiente** (`VULCANFORGE_*`) | as coordenadas do servidor e as credenciais |

**Por que o ambiente vem por último**, e não primeiro: é a mesma razão do lado Java. A
configuração escrita é explícita — alguém a versionou ou a digitou naquela invocação; o
ambiente muda sem ninguém editar nada. Na ordem inversa, uma variável exportada num shell
esquecido passaria a mandar no build de quem configurou o oposto, e nada apareceria no log.

**Coordenadas e credenciais o projeto não declara** — para elas o ambiente é a única fonte,
então não há disputa:

```bash
VULCANFORGE_NEXUS_DOCKER_REGISTRY=192.168.15.34:5000
VULCANFORGE_NEXUS_USERNAME=...
VULCANFORGE_NEXUS_PASSWORD=...
VULCANFORGE_TARGET=nexus            # ou github (ghcr.io)
```

A regra de nome é **idêntica à do lado Java**: prefixo `VULCANFORGE_`, ponto vira `_`, camelCase
vira `SNAKE_CASE`. Isso não é simetria gratuita — a esteira exporta as mesmas variáveis para
projetos Java e .NET, e um segundo dialeto obrigaria cada job a saber com qual mundo está
falando.

### A senha

Vai por **variável de ambiente**, e chega ao Docker por **`--password-stdin`**. Nunca por
argumento: um `--password` a poria em `ps` e em qualquer log que ecoe o comando.

> ⚠️ **A exceção é o `nuget-publish`, e ela é do NuGet.** O `dotnet nuget push` não tem
> `--api-key-stdin` e não lê a chave do ambiente, então ela **vai na linha de comando**. A
> ferramenta a mascara no próprio log; o que ela não alcança é o `ps` da máquina durante os
> segundos do push. Está dito no código, onde a decisão está.

---

## Opções

```
--tag <v>              a tag da imagem. Padrão: a <Version> do projeto
--image-name <n>       padrão: o nome do .csproj, em minúsculas
--namespace <n>        prefixo no registry
--dockerfile-path <p>  padrão: Dockerfile
--context-path <p>     contexto do build. Padrão: .
--docker-target <t>    o estágio a construir (--target do docker)
--build-args <k=v,..>  --build-arg do docker
--labels <k=v,...>     rótulos extras; VENCEM os automáticos
--oci-labels false     desliga os rótulos da OCI (decisão explícita)
--remove-local-image false
--project-dir <p>      padrão: o diretório atual
--package-dir <p>      (nuget-publish) padrão: nupkg
```

---

## ⚠️ Ela não compila nada

Nem o projeto, nem o pacote. `dotnet publish` (ou `dotnet pack`) tem que ter rodado **antes**.

No lado Java o `clean`/`build` é responsabilidade da ferramenta hospedeira; aqui não há
hospedeira, e a decisão é mais forte: **a imagem empacota o que já está em disco**. Se ela
reconstruísse, os bytes publicados não seriam os que passaram pelas conferências do build — e é
quase sempre a mesma coisa, mas o *quase* é o incidente que ninguém reproduz.

---

## Desenvolver

```bash
cd vulcan-forge-dotnet
dotnet build -c Release
dotnet test -c Release
```

São **58 testes**, e eles cobrem o que quebra em silêncio: a ordem de detecção do commit, a
precedência dos rótulos, a regra de nome das variáveis, a trava de goals, a montagem da
referência remota e o fato de a senha **não** aparecer nos argumentos.

Nenhum teste chama `docker`: o `IProcessRunner` é substituído por um duplo que grava os comandos.
