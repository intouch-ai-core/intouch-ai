# GitHub Insights

Who is looking at a repository, where they came from, and what they opened —
[Traffic API](https://docs.github.com/en/rest/metrics/traffic). The same question a website asks of
its access log, asked of a repository, and answered into an email instead of a browser tab.

Part of the GitHub family: [`github`](../github) (issues and pull requests),
[`github-publish`](../github-publish) (files),
[`github-release`](../github-release) (releases and assets),
[`github-repo`](../github-repo) (repository administration).

## Use case

You published a repository and announced it somewhere. Did anyone arrive? From which link? Did they
read past the README? `operation=summary` returns the whole Traffic page as text — schedule it
weekly and the answer arrives without you going to look.

## Setup

1. Create a personal access token at <https://github.com/settings/tokens> with **Administration:
   read** and **Contents: read** on the repository. A classic token needs `repo`.
2. Store it once: **Credentials → New → API Key**, name it (e.g. `github-blueisle`), paste the token.
3. Name that credential on every github-insights task.

## Operations

| operation | what it returns |
|---|---|
| `summary` | the full Traffic page as a formatted text report, plus stars/forks/watchers |
| `views` | raw JSON: total and unique views, per day or week |
| `clones` | raw JSON: total and unique clones |
| `referrers` | raw JSON: top referring sites |
| `paths` | raw JSON: most-visited paths in the repository |
| `rate-limit` | how much API budget the token has left (needs no repository) |

## Two limits that are not bugs

**Traffic needs push access.** A token that can read a public repository still gets `403` here —
these numbers belong to the owner, not the public. The tool says exactly that when it happens.

**The window is fourteen days, fixed.** There is no date-range parameter and GitHub retains nothing
older. A longer trend has to be accumulated: run `summary` on a weekly schedule and keep what it
publishes. That is the difference between having a launch-week number and having a launch-week
number to compare against.

## Pipeline

1. `run` (python, `github_insights.py`) — resolves the token from the bound credential, calls the
   traffic endpoints (four of them for `summary`), renders the report, publishes the counts.

## Publishes

- `result` — the report text (`summary`) or the raw JSON body
- `status` — HTTP status code as a string
- `views`, `uniqueViews`, `clones`, `uniqueCloners` — the 14-day totals as numbers, so a workflow
  can compare them against last week's without parsing the report

## Customization

- `per: "week"` groups views and clones by week instead of day — steadier for a monthly digest.
- Pair with the `message` tool to mail the report, exactly as the website traffic workflows do.
- Counts include automated fetches. GitHub filters some bots and not all, so treat the unique
  figures as the honest ones.

## Source

`tool.iml` (definition) · `github_insights.py` (implementation) · `manifest.yaml` (catalog entry)
