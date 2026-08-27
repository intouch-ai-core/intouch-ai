# GitHub Publish

Put a file into a GitHub repository — create it, update it, read it back or delete it — through the
[Contents API](https://docs.github.com/en/rest/repos/contents). One file per call, committed
straight to a branch. No clone, no working copy, no `git` binary on the server.

Part of the GitHub family: [`github`](../github) (issues and pull requests),
[`github-release`](../github-release) (releases and binary assets),
[`github-repo`](../github-repo) (repository administration),
[`github-insights`](../github-insights) (traffic and popularity).

## Use case

A workflow generates something — a report, a catalogue page, a README that has to match what the
server actually has — and the result belongs in a repository rather than an inbox. This publishes
it, on a schedule if you like, with a real commit message.

## Setup

1. Create a personal access token at <https://github.com/settings/tokens>. Fine-grained, scoped to
   the repositories you publish to, with **Contents: read and write**. A classic token needs `repo`.
2. Store it once: **Credentials → New → API Key**, name it (e.g. `github-blueisle`), paste the
   token as the secret.
3. Name that credential on every github-publish task: `"credentialName": "github-blueisle"`.

**There is no `apiKey` input**, deliberately. A token typed into a workflow travels into that
workflow's exports, its activity log and any repository it is committed to.

## Operations

| operation | what it does | required |
|---|---|---|
| `put-file` | create the file, or update it if it is already there | `owner`, `repo`, `path`, and `content` or `contentFile` |
| `get-file` | read a file back as text, plus its blob sha | `owner`, `repo`, `path` |
| `delete-file` | remove a file, with a commit message | `owner`, `repo`, `path` |
| `list-dir` | list a directory (blank `path` = repository root) | `owner`, `repo` |

### put-file is create-or-update

The Contents API is where most integrations break: creating a file sends no `sha`, but *replacing*
one must send the sha of the blob being replaced or GitHub answers `422`. This tool looks the sha
up itself, so the caller never has to know which case they are in. The published `action` output
says which happened — `created` or `updated`.

### Size

The Contents API caps a single file at **1 MB**, and this tool refuses larger content with a clear
message rather than letting GitHub reject it. Ship anything bigger — an installer, an archive — as
a release asset with [`github-release`](../github-release).

## Pipeline

1. `run` (python, `github_publish.py`) — resolves the token from the bound credential, looks up the
   existing blob sha when writing, calls the Contents API, publishes the response.

## Publishes

- `result` — the response body, or the decoded file text for `get-file`
- `status` — HTTP status code as a string
- `sha` — blob sha of the file after the call (or of the file read)
- `url` — the file's page on github.com, after a successful `put-file`
- `action` — `created` or `updated`

## Customization

- `branch` commits somewhere other than the default branch. The branch must already exist.
- `message` sets the commit message; the default names the file and says it came from InTouch.
- `sha` can be supplied to force a specific parent blob — useful when you have already read the
  file and want the write to fail if it changed underneath you.
- Non-2xx responses are published rather than raised, so a workflow can branch on `status`.

## Source

`tool.iml` (definition) · `github_publish.py` (implementation) · `manifest.yaml` (catalog entry)
