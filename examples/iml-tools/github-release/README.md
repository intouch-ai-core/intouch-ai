# GitHub Release

Cut releases on GitHub and attach the files people download —
[Releases API](https://docs.github.com/en/rest/releases). This is how a build reaches users: a tag,
release notes, and the binaries hanging off them.

Part of the GitHub family: [`github`](../github) (issues and pull requests),
[`github-publish`](../github-publish) (files in the repository),
[`github-repo`](../github-repo) (repository administration),
[`github-insights`](../github-insights) (traffic and popularity).

## Use case

A workflow builds an installer and the installer has to reach users. Committing it is not an
option — the Contents API caps a file at 1 MB. A release asset has no such limit, and it gives you
a stable download URL, a version number and notes in one place.

## Setup

1. Create a personal access token at <https://github.com/settings/tokens> with **Contents: read and
   write** on the target repositories (releases live under the Contents permission, not a separate
   one). A classic token needs `repo`.
2. Store it once: **Credentials → New → API Key**, name it (e.g. `github-blueisle`), paste the token.
3. Name that credential on every github-release task.

## Operations

| operation | what it does | required |
|---|---|---|
| `create-release` | create a release, tagging a commit if the tag is new | `owner`, `repo`, `tag` |
| `list-releases` | releases newest first | `owner`, `repo` |
| `get-release` | one release by `tag` or `releaseId`; neither = the latest | `owner`, `repo` |
| `update-release` | change the title, notes, draft or prerelease flag | `owner`, `repo`, `tag` or `releaseId`, plus a field |
| `delete-release` | remove a release | `owner`, `repo`, `tag` or `releaseId` |
| `list-assets` | files attached to a release, with their ids and sizes | `owner`, `repo`, `tag` or `releaseId` |
| `upload-asset` | attach a file from the server to a release | `owner`, `repo`, `tag` or `releaseId`, `assetFile` |
| `delete-asset` | remove one attached file | `owner`, `repo`, `assetId` |

## Two hosts, one credential

Release metadata goes to `api.github.com`. Asset **bytes** go to `uploads.github.com` — a different
host, and sending an upload to the API host answers `404` with no explanation. The tool handles
both; the distinction is documented here because it is the usual reason a hand-rolled version fails.

## Drafts have no tag

A draft release does not create its git tag until you publish it — GitHub labels it
`untagged-<hash>` — so `GET /releases/tags/{tag}` answers 404 for a draft even though the draft
carries the `tag_name` you gave it. Uploading assets to a draft and publishing afterwards is the
normal release flow, so the tool falls back to scanning the release list, where drafts do appear.
`tag` therefore addresses a draft and a published release alike.

## Memory

GitHub's upload endpoint does not accept chunked transfer, so an asset is read into memory in one
go. A 400 MB installer needs 400 MB of headroom in the server process. The step's timeout is 900
seconds for the same reason — a large file over a domestic uplink takes minutes.

## Pipeline

1. `run` (python, `github_release.py`) — resolves the token from the bound credential, resolves the
   release from `tag` when no `releaseId` was given, calls the endpoint, publishes the response.

## Publishes

- `result` — the raw JSON response body
- `status` — HTTP status code as a string
- `releaseId` — the release acted on, ready to feed the next task
- `assetId`, `url`, `bytes` — after an upload: the asset's id, its `browser_download_url`, its size

## Customization

- `draft: "true"` builds the release invisibly; publish it later with `update-release`.
- `prerelease: "true"` keeps it out of the "Latest" slot.
- `targetCommitish` names the branch or commit to tag when the tag does not exist yet.
- **Deleting a release does not delete its git tag.** The result says so explicitly, because the
  expectation that it does is where people get confused.

## Source

`tool.iml` (definition) · `github_release.py` (implementation) · `manifest.yaml` (catalog entry)
