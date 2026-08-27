# GitHub

Read and create GitHub data through the [GitHub REST API](https://docs.github.com/en/rest) —
issues, pull requests, releases and repository metadata.

This is the read-and-triage member of the GitHub family. Its siblings do the writing:
[`github-publish`](../github-publish) (files), [`github-release`](../github-release) (releases and
assets), [`github-repo`](../github-repo) (repository administration) and
[`github-insights`](../github-insights) (traffic and popularity).

## Use case

Open an issue automatically when a workflow fails, mail yourself the open issues each morning,
or check what shipped in the last release — without leaving InTouch.

## Setup

1. Create a personal access token at <https://github.com/settings/tokens>. A fine-grained token
   scoped to the repositories you care about is the better choice; it needs **Issues: read/write**
   and **Contents: read** for the operations here. A classic token needs `repo`.
2. Store it once: **Credentials → New → API Key**, name it (e.g. `github-blueisle`), paste the
   token as the secret.
3. Name that credential on every github task: `"credentialName": "github-blueisle"`.

**There is no `apiKey` input.** Version 2.0.0 removed it. A token typed into a workflow travels
into that workflow's exports, its activity log and any repository it is committed to — the exact
exposure a vault exists to prevent. The credential is the only way in.

## Operations

| operation | what it does | required |
|---|---|---|
| `list-issues` | open/closed/all issues on a repository | `owner`, `repo` |
| `get-issue` | one issue with its body and labels | `owner`, `repo`, `issueNumber` |
| `create-issue` | open a new issue | `owner`, `repo`, `title` |
| `list-prs` | pull requests, filtered by state | `owner`, `repo` |
| `list-releases` | published releases, newest first | `owner`, `repo` |
| `get-repo` | repository metadata — default branch, visibility, counts | `owner`, `repo` |

`state` filters `list-issues` and `list-prs`: `open` (default), `closed` or `all`.
`labels` attaches a comma-separated label list when creating an issue.

## Pipeline

1. `run` (python, `github.py`) — resolves the token from the bound credential, calls one REST
   endpoint, and publishes the response.

## Publishes

- `result` — the raw JSON response body
- `status` — the HTTP status code as a string

## Customization

Non-2xx responses are returned rather than raised, so a workflow can branch on `status` — a `404`
from `get-issue` is data, not a failure. Transport failures do fail the step.

## Source

`tool.iml` (definition) · `github.py` (implementation) · `manifest.yaml` (catalog entry)
