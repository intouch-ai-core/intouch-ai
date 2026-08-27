# GitHub Repository Admin

Create, describe and retire GitHub repositories — [Repos API](https://docs.github.com/en/rest/repos).
The operations you rarely need and then need badly, at a moment when clicking through a website is
the wrong answer.

Part of the GitHub family: [`github`](../github) (issues and pull requests),
[`github-publish`](../github-publish) (files),
[`github-release`](../github-release) (releases and assets),
[`github-insights`](../github-insights) (traffic and popularity).

## Use case

Provision a repository with the same description, homepage and topics every time. Audit what an
organisation owns and whether any of it is public by accident. Archive what is finished. Delete
what should not exist.

## Setup

1. Create a personal access token at <https://github.com/settings/tokens>.
   - Creating, editing, archiving: **Administration: write** (fine-grained) or `repo` (classic).
   - **Deleting also needs `delete_repo`** — see below.
2. Store it once: **Credentials → New → API Key**, name it (e.g. `github-blueisle`), paste the token.
3. Name that credential on every github-repo task.

## Operations

| operation | what it does | required |
|---|---|---|
| `create-repo` | create a repository, in an org or under the token owner | `repo` (+ `org` for an org) |
| `get-repo` | metadata — visibility, default branch, counts | `owner`, `repo` |
| `update-repo` | description, homepage, default branch, rename, private, archived | `owner`, `repo` + a field |
| `set-topics` | replace the repository's topic list | `owner`, `repo`, `topics` |
| `list-repos` | everything an org or user owns, newest activity first | `org` or `owner` |
| `delete-repo` | delete a repository, permanently | `owner`, `repo`, `confirm` |

## Two things that will bite you

**Scopes are not one thing.** Creating a repository needs `repo` / Administration: write. *Deleting*
one additionally needs **`delete_repo`**, a separate scope that `gh auth login` does not grant by
default — because deletion cannot be undone. A token that creates repositories happily will still
answer `403` on delete. When that happens the tool says exactly this rather than reporting a bare
permission error.

**`delete-repo` refuses to run without `confirm`.** You must pass the exact `owner/repo` string
being deleted. A typo in `repo` would otherwise delete whatever that typo happens to name, and there
is no undo — GitHub keeps a short restore window for some accounts and no guarantee for any of them.

## Defaults worth knowing

- `create-repo` makes the repository **private** unless `private` is `"false"`. Public is a decision,
  not an accident.
- `autoInit: "true"` gives the new repository an initial commit, so it has a default branch that
  [`github-publish`](../github-publish) can immediately commit to. Without it, the first write to an
  empty repository answers `409`.
- `set-topics` **replaces** the topic list. Pass every topic you want, not just the new one.
- Archiving through the API is one-way — GitHub does not support unarchiving through it.

## Pipeline

1. `run` (python, `github_repo.py`) — resolves the token from the bound credential, calls one Repos
   endpoint, publishes the response.

## Publishes

- `result` — the raw JSON response body
- `status` — HTTP status code as a string
- `fullName`, `url` — after `create-repo`: `owner/name` and the repository's page

## Source

`tool.iml` (definition) · `github_repo.py` (implementation) · `manifest.yaml` (catalog entry)
