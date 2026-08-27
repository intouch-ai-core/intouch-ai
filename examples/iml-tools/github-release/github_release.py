#!/usr/bin/env python3
"""github-release — cut GitHub releases and attach files to them.

A release is how a binary reaches users: the tag, the notes, and the files people download.
The Contents API cannot carry those files (1 MB cap); the uploads host can, and that is the
difference between this tool and github-publish.

InTouch tool convention: the script receives the path to a context JSON file as sys.argv[1],
reads ctx["input"] and ctx["credential"], and prints one JSON object whose keys become this
step's outputs.

The token comes from the BOUND CREDENTIAL and only from there.

Grounded in https://docs.github.com/en/rest/releases :
  create-release  POST   /repos/{o}/{r}/releases            {tag_name, name, body, draft, prerelease}
  list-releases   GET    /repos/{o}/{r}/releases
  get-release     GET    /repos/{o}/{r}/releases/tags/{tag}  (or /releases/{id})
  update-release  PATCH  /repos/{o}/{r}/releases/{id}
  delete-release  DELETE /repos/{o}/{r}/releases/{id}
  list-assets     GET    /repos/{o}/{r}/releases/{id}/assets
  upload-asset    POST   https://uploads.github.com/repos/{o}/{r}/releases/{id}/assets?name=
  delete-asset    DELETE /repos/{o}/{r}/releases/assets/{asset_id}

TWO HOSTS, one credential. Metadata goes to api.github.com; asset bytes go to uploads.github.com.
Sending a large upload to the API host is the usual mistake and answers 404 with no explanation.

Assets are streamed from disk in one read: GitHub's upload endpoint does not accept chunked
transfer, so the file is held in memory. A 400 MB installer needs 400 MB of headroom.
"""
import json
import mimetypes
import os
import sys
import urllib.error
import urllib.parse
import urllib.request

API_BASE = "https://api.github.com"
UPLOAD_BASE = "https://uploads.github.com"
API_VERSION = "2022-11-28"
MAX_BODY = 5 * 1024 * 1024
TIMEOUT = 60
UPLOAD_TIMEOUT = 900          # a large installer over a home connection takes minutes


# Every key named in tool.iml's `publish` block must be present on EVERY call. A publish
# reference to a key this run did not emit fails the whole task — even when the HTTP call
# succeeded — so the optional ones are declared here and default to empty.
PUBLISHED = ('releaseId', 'assetId', 'url', 'bytes')


def emit(result, status, error=None, **extra):
    out = {"result": result, "status": str(status)}
    for key in PUBLISHED:
        out[key] = ""
    if error:
        out["error"] = error
    out.update({k: ("" if v is None else str(v)) for k, v in extra.items()})
    print(json.dumps(out))


def fail(msg, code=1):
    emit("", "0", msg)
    sys.exit(code)


def resolve_token(cred):
    """The bound credential's token. Never logged, never echoed, never read from an input."""
    for field in ("apiKey", "key", "secret", "token"):
        value = cred.get(field)
        if isinstance(value, str) and value.strip():
            return value.strip()
    fail("No GitHub token. Attach an API Key credential holding a personal access token "
         "(credentialName on the task). Create the token at https://github.com/settings/tokens "
         "with Contents: read and write — releases live under the Contents permission.")


def request(method, url, token, payload=None, raw=None, content_type=None, timeout=TIMEOUT):
    """One authenticated call. Returns (status, body_text). Non-2xx is returned, not raised."""
    if raw is not None:
        data = raw
    elif payload is not None:
        data = json.dumps(payload).encode("utf-8")
    else:
        data = None
    req = urllib.request.Request(url, data=data, method=method)
    req.add_header("Authorization", "Bearer " + token)
    req.add_header("Accept", "application/vnd.github+json")
    req.add_header("X-GitHub-Api-Version", API_VERSION)
    req.add_header("User-Agent", "InTouch-AI/github-release")
    if content_type:
        req.add_header("Content-Type", content_type)
    elif payload is not None:
        req.add_header("Content-Type", "application/json")
    try:
        with urllib.request.urlopen(req, timeout=timeout) as r:
            return r.status, r.read(MAX_BODY).decode("utf-8", "replace")
    except urllib.error.HTTPError as e:
        body = ""
        try:
            body = e.read(MAX_BODY).decode("utf-8", "replace")
        except Exception:
            pass
        return e.code, body
    except Exception as e:
        fail("request failed: %s: %s" % (type(e).__name__, e))


def hint_for(status):
    if status == 401:
        return " — the token was rejected; check it at https://github.com/settings/tokens"
    if status == 403:
        return " — the token lacks Contents: write on this repository"
    if status == 404:
        return " — repository, release or asset not found (an asset upload sent to api.github.com instead of uploads.github.com also answers 404)"
    if status == 422:
        return " — GitHub rejected the payload; a tag that already has a release, or an asset name already attached"
    return ""


def as_bool(value, default=False):
    text = (value or "").strip().lower()
    if not text:
        return default
    return text in ("1", "true", "yes", "on")


def release_id(base, token, inp):
    """The release to act on: an explicit releaseId, or the release owning `tag`."""
    explicit = (inp.get("releaseId") or "").strip()
    if explicit:
        return explicit
    tag = (inp.get("tag") or "").strip()
    if not tag:
        fail("this operation needs `releaseId`, or `tag` so the release can be looked up")
    status, body = request("GET", "%s/releases/tags/%s" % (base, urllib.parse.quote(tag)), token)
    if status == 404:
        # A DRAFT release has no git tag yet — GitHub calls it "untagged-<hash>" and
        # /releases/tags/{tag} cannot find it, even though the draft carries the tag_name you
        # asked for. Uploading assets to a draft before publishing it is the normal release
        # flow, so fall back to scanning the list, which does include drafts for a token that
        # can see them.
        status, body = request("GET", base + "/releases?per_page=100", token)
        if status >= 400:
            fail("could not list releases while looking for tag '%s': HTTP %d%s"
                 % (tag, status, hint_for(status)))
        try:
            for release in json.loads(body):
                if release.get("tag_name") == tag:
                    return str(release.get("id") or "")
        except Exception:
            pass
        fail("no release — published or draft — carries the tag '%s' in this repository" % tag)
    if status >= 400:
        fail("could not look up the release for tag '%s': HTTP %d%s" % (tag, status, hint_for(status)))
    try:
        return str(json.loads(body).get("id") or "")
    except Exception:
        fail("could not read the release id out of GitHub's response")


def main():
    if len(sys.argv) < 2:
        fail("context file path not provided")
    try:
        ctx = json.load(open(sys.argv[1]))
    except Exception as e:
        fail("cannot read context file: %s: %s" % (type(e).__name__, e))

    inp = ctx.get("input") or {}
    token = resolve_token(ctx.get("credential") or {})

    op = (inp.get("operation") or "").strip().lower()
    owner = (inp.get("owner") or "").strip()
    repo = (inp.get("repo") or "").strip()
    known = ("create-release", "list-releases", "get-release", "update-release",
             "delete-release", "list-assets", "upload-asset", "delete-asset")
    if op not in known:
        fail("unknown operation '%s'. one of: %s" % (op, ", ".join(known)))
    for name, value in (("owner", owner), ("repo", repo)):
        if not value:
            fail("'%s' is required" % name)

    base = "%s/repos/%s/%s" % (API_BASE, urllib.parse.quote(owner), urllib.parse.quote(repo))

    if op == "list-releases":
        status, body = request("GET", base + "/releases?per_page=30", token)
        emit(body, status, None if status < 400 else "HTTP %d%s" % (status, hint_for(status)))
        sys.exit(0 if status < 400 else 1)

    if op == "get-release":
        rid = (inp.get("releaseId") or "").strip()
        tag = (inp.get("tag") or "").strip()
        if rid:
            url = "%s/releases/%s" % (base, urllib.parse.quote(rid))
        elif tag:
            url = "%s/releases/tags/%s" % (base, urllib.parse.quote(tag))
        else:
            url = base + "/releases/latest"
        status, body = request("GET", url, token)
        emit(body, status, None if status < 400 else "HTTP %d%s" % (status, hint_for(status)))
        sys.exit(0 if status < 400 else 1)

    if op == "create-release":
        tag = (inp.get("tag") or "").strip()
        if not tag:
            fail("'tag' is required — the git tag this release points at, e.g. v8.0.5")
        payload = {
            "tag_name": tag,
            "name": (inp.get("name") or tag).strip(),
            "body": inp.get("body") or "",
            "draft": as_bool(inp.get("draft")),
            "prerelease": as_bool(inp.get("prerelease")),
        }
        target = (inp.get("targetCommitish") or "").strip()
        if target:
            # Only meaningful when the tag does not exist yet — GitHub creates it here.
            payload["target_commitish"] = target
        status, body = request("POST", base + "/releases", token, payload)
        rid = ""
        url = ""
        if 200 <= status < 300:
            try:
                parsed = json.loads(body)
                rid = str(parsed.get("id") or "")
                url = parsed.get("html_url") or ""
            except Exception:
                pass
        emit(body, status, None if status < 400 else "HTTP %d%s" % (status, hint_for(status)),
             releaseId=rid, url=url)
        sys.exit(0 if status < 400 else 1)

    if op == "update-release":
        rid = release_id(base, token, inp)
        payload = {}
        for field, key in (("name", "name"), ("body", "body")):
            if inp.get(field) is not None and str(inp.get(field)).strip() != "":
                payload[key] = inp.get(field)
        if (inp.get("draft") or "").strip():
            payload["draft"] = as_bool(inp.get("draft"))
        if (inp.get("prerelease") or "").strip():
            payload["prerelease"] = as_bool(inp.get("prerelease"))
        if not payload:
            fail("update-release needs at least one of: name, body, draft, prerelease")
        status, body = request("PATCH", "%s/releases/%s" % (base, rid), token, payload)
        emit(body, status, None if status < 400 else "HTTP %d%s" % (status, hint_for(status)), releaseId=rid)
        sys.exit(0 if status < 400 else 1)

    if op == "delete-release":
        rid = release_id(base, token, inp)
        status, body = request("DELETE", "%s/releases/%s" % (base, rid), token)
        # Deleting a release does NOT delete its git tag — say so, because people expect it to.
        emit(body or "release %s deleted (its git tag remains)" % rid, status,
             None if status < 400 else "HTTP %d%s" % (status, hint_for(status)), releaseId=rid)
        sys.exit(0 if status < 400 else 1)

    if op == "list-assets":
        rid = release_id(base, token, inp)
        status, body = request("GET", "%s/releases/%s/assets?per_page=100" % (base, rid), token)
        emit(body, status, None if status < 400 else "HTTP %d%s" % (status, hint_for(status)), releaseId=rid)
        sys.exit(0 if status < 400 else 1)

    if op == "delete-asset":
        asset = (inp.get("assetId") or "").strip()
        if not asset:
            fail("'assetId' is required for delete-asset — list-assets reports the ids")
        status, body = request("DELETE", "%s/releases/assets/%s" % (base, urllib.parse.quote(asset)), token)
        emit(body or "asset %s deleted" % asset, status,
             None if status < 400 else "HTTP %d%s" % (status, hint_for(status)))
        sys.exit(0 if status < 400 else 1)

    # upload-asset
    path = (inp.get("assetFile") or "").strip()
    if not path:
        fail("'assetFile' is required for upload-asset — the path ON THE SERVER of the file to attach")
    if not os.path.isfile(path):
        fail("assetFile not found on the server: %s" % path)
    rid = release_id(base, token, inp)
    name = (inp.get("assetName") or "").strip() or os.path.basename(path)
    guessed = mimetypes.guess_type(name)[0] or "application/octet-stream"
    with open(path, "rb") as f:
        blob = f.read()
    url = "%s/repos/%s/%s/releases/%s/assets?name=%s" % (
        UPLOAD_BASE, urllib.parse.quote(owner), urllib.parse.quote(repo), rid,
        urllib.parse.quote(name))
    status, body = request("POST", url, token, raw=blob, content_type=guessed,
                           timeout=UPLOAD_TIMEOUT)
    download = ""
    asset_id = ""
    if 200 <= status < 300:
        try:
            parsed = json.loads(body)
            download = parsed.get("browser_download_url") or ""
            asset_id = str(parsed.get("id") or "")
        except Exception:
            pass
    emit(body, status, None if status < 400 else "HTTP %d%s" % (status, hint_for(status)),
         releaseId=rid, assetId=asset_id, url=download, bytes=len(blob))
    sys.exit(0 if status < 400 else 1)


if __name__ == "__main__":
    main()
