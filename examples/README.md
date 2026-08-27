# Examples

Working code, not sketches. Every connector here is a tool that ships in InTouch and runs in
production; every IML tool here was exercised against the live GitHub API. Nothing was written
for this repository.

```bash
git clone https://github.com/intouch-ai-core/intouch-ai
cd intouch-ai
./gradlew :examples:connectors:cassandra:jar
```

That is the whole toolchain. The examples depend on `:tool-api` by project reference, so a fresh
clone compiles with no artifact hosting and no Maven coordinate.

## Connectors — Kotlin, compiled, `IToolConnector`

Six, chosen because each teaches a different shape. The only Blue Isle import in any of them is
`com.blueisle.intouch.tool.IToolConnector`.

| | lines | what it shows |
|---|---|---|
| [`excel`](connectors/excel) | 218 | The floor. Local files, no network, no credential, no auth. Read this one first |
| [`git`](connectors/git) | 208 | Wrapping a CLI through process exec — the shortest path from a command you already run to a task type |
| [`clickhouse`](connectors/clickhouse) | 527 | A REST connector with **no vendor driver at all** — `java.net.http` and nothing else |
| [`mongodb`](connectors/mongodb) | 256 | A vendor driver, a connection string, a document store |
| [`ldap`](connectors/ldap) | 255 | JNDI, and a third authentication shape: bind DN and password |
| [`cassandra`](connectors/cassandra) | 787 | The full article. Driver, credential model, session lifecycle, typed result mapping, TLS truststore |

Each directory is self-contained: `build.gradle.kts`, `connector.json` (the manifest InTouch
reads), `README.md`, and one source file. `cassandra` and `clickhouse` also carry the `help.html`
the server serves to the AI assistant and the UI.

### Installing one

Build it, then drop the JAR into `tools/installed/` under your InTouch home. The server loads it
in its own `URLClassLoader` — see [`tool-api/README.md`](../tool-api/README.md) for why that
matters and what it means for your dependencies.

## IML tools — declarative, no compilation

Five, and they are the GitHub family InTouch itself uses. An IML tool is `tool.iml` (steps and
their schema), a script, a `manifest.yaml`, and a README.

| | |
|---|---|
| [`github`](iml-tools/github) | Issues, pull requests, releases, repository metadata |
| [`github-repo`](iml-tools/github-repo) | Create, update, topics, list, delete. 45 lines of IML — start here |
| [`github-publish`](iml-tools/github-publish) | Put, get, delete a file; list a directory |
| [`github-release`](iml-tools/github-release) | Releases and their assets |
| [`github-insights`](iml-tools/github-insights) | Traffic summary |

Two things in these are worth more than the code, because only running them surfaced either:

**Every key a `publish` block declares must be emitted on every call.** A `{{run.url}}` your
script did not emit fails the whole task even when the HTTP call returned 200. Each script here
declares its published keys up front and defaults them to `""`.

**A draft release has no git tag.** It gets `untagged-<hash>`, so `/releases/tags/{tag}` 404s on
it and `release_id()` falls back to scanning `/releases`, where drafts do appear.

`github` is also the reason these take an **apikey credential** rather than a token argument.
Version 1.0.0 accepted the personal access token as a plain input, which put it in the workflow,
in its exports, and in the AI assistant's context — the exact thing the credential vault exists
to prevent. 2.0.0 reads nothing secret from its arguments.
