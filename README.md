<div align="center">

# InTouch AI

### Describe it. It runs itself.

**Production-proven. AI-native. Free to start.**

[![License](https://img.shields.io/badge/Personal-Free%20EULA-green.svg)](LICENSE.txt)
[![Version](https://img.shields.io/badge/version-8.0.6-blue.svg)](https://github.com/intouch-ai-core/intouch-ai)
[![Kotlin](https://img.shields.io/badge/Kotlin-JVM%2017-orange.svg)](https://kotlinlang.org/)
[![Platform](https://img.shields.io/badge/platform-Linux%20%7C%20Windows%20%7C%20macOS%20%7C%20ARM-lightgrey.svg)](https://github.com/intouch-ai-core/intouch-ai)

</div>

---

Automation you describe in plain English. Tell the AI assistant what you want done; it builds the
automation, schedules it, runs it, and shows you proof it ran. Your credentials stay in an
encrypted vault the AI cannot read.

**Personal Edition is free forever** — no licence key, no trial, no expiry.

## Install

Needs **Java 17+**. On Windows the installer installs Python for you.

**Get `install-intouch-ai.zip`** — from
[Releases](https://github.com/intouch-ai-core/intouch-ai/releases/latest) or
[blueisle.com](https://www.blueisle.com/downloads/install-intouch-ai.zip). It is the complete
installation, ~240 MB: the server, the UI, the docs, the starter tools and workflows, and
`install.py`, which copies them where you choose. Nothing is fetched over the network, so it
installs on a machine with no internet.

GitHub's green **Code ▸ Download ZIP** button is not this. That button archives the repository,
which holds only this README and the licence.

**Windows** — extract `install-intouch-ai.zip`, double-click **`install.bat`**

**Linux / macOS**

```bash
unzip install-intouch-ai.zip -d install-intouch-ai
cd install-intouch-ai
python3 install.py
```

Log in at **http://localhost:2200** (`intouch` / `blueisle`), paste an
[openrouter.ai](https://openrouter.ai) key into the **Get Started** panel, and type what you want.

**Put a dollar on the key** — a free key is good for a handful of calls and then stops.
Or run a local Ollama model instead, with no key and no cost.

Start and stop with `python3 bin/start.py` and `python3 bin/stop.py`.

## What's in it

| | |
|---|---|
| **33 tool types** | HTTP, AWS (the entire CLI v2), Google Workspace, email (IMAP read), messaging, OS/spawn, archive, runtime environments, vector store, skills, workflows and the AI family — every one scheduled, retried, logged and alerted the same way. SQL, SSH, FTP/SFTP, rsync and Docker are among the hundreds that install from the Hub |
| **8 AI providers** | OpenRouter (recommended — one key reaches 367 models), Anthropic, OpenAI, Gemini, Mistral, DeepSeek, xAI, and Ollama for fully local |
| **35 credential types** | AES-256-GCM vault; secrets are redacted out of every API response, so the AI can use a credential without being given it |
| **AI assistant** | Reads the real documentation and your real install. Builds workflows, runs tools, explains failures |
| **275-tool MCP server** | Drive InTouch from Claude Code or any MCP client. InTouch is an MCP *client* too |
| **Alerts on 4 channels** | Email, Slack, Discord, Telegram — outbound (SMS, WhatsApp, Teams and LINE on Department and Enterprise) |
| **7 schedule types** | Plus file triggers, folder triggers, AI-condition triggers, and ad-hoc OneShot runs |
| **IML** | Workflows and tools as code — plain JSON, diffable, reviewable, portable between installs |
| **Custom tools** | Implement [`IToolConnector`](tool-api) — 189 lines, MIT — drop in a JAR, and your tool is a first-class citizen. [Six working examples](examples/connectors) |
| **Runs on a Pi** | Single JAR, embedded database, zero external dependencies. A Raspberry Pi 5 is enough |

## Already using Claude Code?

Then you have the authoring half of the problem solved, and this is the other half.

An AI coding agent decides what should happen. It cannot make it *keep* happening — it cannot
wake up at 03:00, wait four hours for a condition without burning context, do the identical
thing on the hundredth run, or hold a production credential it has not read. Those are runtime
problems, not intelligence problems.

InTouch runs as a service on the same machine as your terminal:

```
your terminal          InTouch AI (background service)
─────────────          ────────────────────────────────
Claude Code    ──MCP──▶ 275 tools · 511 REST endpoints
  authors,              schedules · triggers · retries · alerts
  proves it,            AES-256-GCM credential vault the AI cannot read
  hands it over         attributed audit log · frozen, zero-cost runs
```

Point your agent at the MCP server once, then talk:

> *"Store these API credentials in InTouch, then build a workflow that pulls yesterday's orders
> into the warehouse."* · *"Run it once and show me the log."* · *"Schedule it for 02:00 and
> alert me on failure."* · *"Freeze it — no AI in the execution path from here."*

Four sentences, and the thing exists, is proven, is scheduled, is monitored, and no longer costs
a token to run.

**[Notes from Claude Code](https://blueisle.com/for-claude-code/)** — Anthropic's CLI agent wrote
its own account of what it hands over and why, after working inside a running InTouch server.

## Determinism when you want it

Most platforms make you choose: hand-write every step, or hand everything to a model. InTouch
spans the range, and you pick per workflow.

- **Fully deterministic** — no model anywhere in the execution path
- **Fully AI** — the assistant reasons and acts on every run
- **AI authors once, then deterministic forever** — describe it in English, let the assistant
  generate the workflow, then run that workflow with zero AI cost per run

Enterprises tend to run deterministically in production while using AI from conception through
authoring. Newcomers start heavily AI and migrate the parts that matter to determinism.

## Editions

| Edition | What it adds |
|---|---|
| **Personal** | Free forever. One user, 25 contacts, everything else unrestricted |
| **Solo** | Your own named login, unlimited contacts, areas and tracks |
| **Team** | Up to 5 users, shared workflows, credentials and run history |
| **Department** | Role-based access control, area isolation, LDAP / Active Directory, CyberArk, import/export |
| **Enterprise** | Everything, plus TLS and cipher governance, SSO, and live workflow monitoring |

Every edition runs the same code. Personal stays simple by hiding the areas/tracks hierarchy —
work lands in a default bucket created at startup. You need a licensed edition when you want
named or multiple users, that hierarchy, or governance.

## Documentation

**Ask the assistant.** It reads the complete documentation and can see what is installed on your
machine, so it answers about *your* install rather than a generic one.

Everything else is at **[blueisle.com](https://blueisle.com)** —
[editions](https://blueisle.com/editions.html) ·
[use cases](https://blueisle.com/use-cases.html) ·
[architecture](https://blueisle.com/architecture.html) ·
[security](https://blueisle.com/security.html) ·
[comparisons](https://blueisle.com/compare/)

## Community

- **[Discord](https://discord.gg/Egmpjch9)** — fastest Q&A
- **[Slack](https://join.slack.com/t/intouchcommunity/shared_invite/zt-3xld2ef1s-9gX9PHzJ46XafO973EIIpA)**
- **[Issues](https://github.com/intouch-ai-core/intouch-ai/issues)** — bugs and feature requests
- **[InTouch Hub](https://hub.blueisle.com)** — browse and publish tools

The platform is commercial; the **ecosystem on top of it is open**, and it is in this
repository:

- **[`tool-api/`](tool-api)** — `IToolConnector`, 189 lines, MIT. The entire plugin ABI
- **[`examples/connectors/`](examples/connectors)** — six compiled tools that ship in the
  product: `excel`, `git`, `clickhouse`, `mongodb`, `ldap`, `cassandra`
- **[`examples/iml-tools/`](examples/iml-tools)** — five declarative tools, the GitHub family
  InTouch uses on itself

```bash
git clone https://github.com/intouch-ai-core/intouch-ai
cd intouch-ai && ./gradlew :examples:connectors:cassandra:jar
```

Install what you build from anywhere a file lives — a private git repo, an internal artifact
server, a coworker's share, or the Hub. The Hub is a convenience, not a required channel.

The server itself is commercial and its source is not published.

## Licence

Personal Edition is free under the [EULA](LICENSE.txt). Solo, Team, Department and Enterprise are
commercially licensed — see [blueisle.com/editions.html](https://blueisle.com/editions.html).

<div align="center">

**Blue Isle Software** — automation since 1996

[Download](https://github.com/intouch-ai-core/intouch-ai/releases) ·
[Discord](https://discord.gg/Egmpjch9) ·
[Issues](https://github.com/intouch-ai-core/intouch-ai/issues)

</div>
