# Publicação no Maven Central (mantenedores)

Este guia é para **publicar** o Vulcan Forge no Maven Central. Consumidores não precisam
disto — veja o [README](README.md).

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

Configure os **secrets** do repositório (Settings → Secrets and variables → Actions):

| Secret                  | Valor                                    |
|-------------------------|------------------------------------------|
| `CENTRAL_USERNAME`      | username do token do Central             |
| `CENTRAL_PASSWORD`      | password do token do Central             |
| `MAVEN_GPG_PRIVATE_KEY` | conteúdo de `private-key.asc`            |
| `MAVEN_GPG_PASSPHRASE`  | passphrase da chave GPG                  |

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

## 5. Após publicar

- Leva alguns minutos até sincronizar para o `repo.maven.apache.org`.
- Atualize a versão no README/exemplos se necessário e faça o bump para a próxima
  versão de desenvolvimento.
