# Git Tool

Git repository operations via the Git CLI.

## Tool ID
`git`

## Credential Required
Yes — for authenticated Git operations.

### Credential Properties

| Property | Type | Default | Description |
|----------|------|---------|-------------|
| `username` | string | — | Git username (for HTTPS repos) |
| `password` | string | — | Git password or personal access token |
| `sshKeyPath` | string | — | Path to SSH private key (for SSH repos) |

Credentials are injected via environment variables (`GIT_USERNAME`, `GIT_PASSWORD`, `GIT_SSH_COMMAND`).

## Operations

All operations require `localPath` (local repository directory) except `clone`.

| Property | Type | Default | Description |
|----------|------|---------|-------------|
| `operation` | string | — | **Required.** Git operation |
| `localPath` | string | — | **Required.** Local repository path |
| `timeoutSeconds` | integer | `300` | Operation timeout |

### 1. `clone` — Clone a repository

| Property | Type | Default | Description |
|----------|------|---------|-------------|
| `repoUrl` | string | — | **Required.** Repository URL |
| `branch` | string | — | Branch to clone |

### 2. `pull` — Pull latest changes

No additional properties.

### 3. `push` — Push to remote

| Property | Type | Default | Description |
|----------|------|---------|-------------|
| `branch` | string | — | Branch to push (optional) |

### 4. `commit` — Commit changes

| Property | Type | Default | Description |
|----------|------|---------|-------------|
| `message` | string | — | **Required.** Commit message |
| `addAll` | boolean | `true` | Stage all changes before commit (`git add -A`) |

### 5. `checkout` — Switch branches

| Property | Type | Default | Description |
|----------|------|---------|-------------|
| `branch` | string | — | **Required.** Branch name |

### 6. `status` — Show working tree status

Returns short status output. **Published:** `output`

### 7. `log` — Show commit log

| Property | Type | Default | Description |
|----------|------|---------|-------------|
| `maxEntries` | integer | `10` | Max log entries |

Returns oneline log. **Published:** `output`

### 8. `diff` — Show changes

Returns diff stat. **Published:** `output`

### 9. `tag` — Create or list tags

| Property | Type | Default | Description |
|----------|------|---------|-------------|
| `tag` | string | — | Tag name (blank = list all tags) |

**Published Outputs (all operations):**
- `operation` — the operation performed
- `output` — command stdout

## Killable
Yes — destroys the Git process.

## Chaining Patterns

- **Git clone/pull → Runtime Env** — pull code, then run tests or builds
- **Git clone/pull → Docker** — pull code, then build/run in container
- **Git commit + push** — chain commit then push in a multi-task job

## Prerequisites
- `git` CLI must be on the server PATH
