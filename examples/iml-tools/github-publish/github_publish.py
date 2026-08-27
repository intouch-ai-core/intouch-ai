#!/usr/bin/env python3
"""github-publish — put files into a GitHub repository through the Contents API.

This is how an InTouch workflow publishes: a README it generated, a report it built, a
configuration file it changed. One file per call, committed straight to a branch — no clone,
no working copy, no git binary on the server.

InTouch tool convention: the script receives the path to a context JSON file as sys.argv[1],
reads ctx["input"] and ctx["credential"], and prints one JSON object whose keys become this
step's outputs.

The token comes from the BOUND CREDENTIAL and only from there — never from an input, because
an input is stored in the workflow and travels into its exports and activity log.

Grounded in https://docs.github.com/en/rest/repos/contents :
  put-file     PUT    /repos/{owner}/{repo}/contents/{path}   {message, content(b64), branch, sha?}
  get-file     GET    /repos/{owner}/{repo}/contents/{path}?ref={branch}
  delete-file  DELETE /repos/{owner}/{repo}/contents/{path}   {message, sha, branch}
  list-dir     GET    /repos/{owner}/{repo}/contents/{dir}?ref={branch}

THE SHA RULE, which is where every Contents API integration goes wrong: creating a file sends no
`sha`; UPDATING one must send the sha of the blob being replaced, or GitHub answers 422. This tool
fetches the sha itself when the file already exists, so `put-file` is create-or-update and the
caller never has to know which case they are in.

Standard library only. Bodies capped at 5 MB — the Contents API's own limit for this endpoint is
1 MB per file, and files above that need the Git Data API (blobs + trees), which this tool does
not attempt.
"""
import base64
import json
import os
import sys
import urllib.error
import urllib.parse
import urllib.request

API_BASE = "https://api.github.com"
API_VERSION = "2022-11-28"
MAX_BODY = 5 * 1024 * 1024
CONTENTS_LIMIT = 1024 * 1024      # GitHub's documented per-file cap on this endpoint
TIMEOUT = 60


# Every key named in tool.iml's `publish` block must be present on EVERY call. A publish
# reference to a key this run did not emit fails the whole task — even when the HTTP call
# succeeded — so the optional ones are declared here and default to empty.
PUBLISHED = ('sha', 'url', 'action')


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
         "with Contents: read and write on the repositories you publish to.")


def request(method, url, token, payload=None):
    """One authenticated GitHub call. Returns (status, body_text). Non-2xx is returned, not raised."""
    data = json.dumps(payload).encode("utf-8") if payload is not None else None
    req = urllib.request.Request(url, data=data, method=method)
    req.add_header("Authorization", "Bearer " + token)
    req.add_header("Accept", "application/vnd.github+json")
    req.add_header("X-GitHub-Api-Version", API_VERSION)
    req.add_header("User-Agent", "InTouch-AI/github-publish")
    if data is not None:
        req.add_header("Content-Type", "application/json")
    try:
        with urllib.request.urlopen(req, timeout=TIMEOUT) as r:
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
        return " — the token lacks permission for this repository (Contents: write)"
    if status == 404:
        return " — repository, branch or path not found, or the token cannot see a private repo"
    if status == 409:
        return " — the branch is out of date, or the repository is empty and has no branch yet"
    if status == 422:
        return " — GitHub rejected the payload; usually a stale sha or an invalid branch name"
    return ""


def content_bytes(inp):
    """The bytes to publish: inline `content`, or `contentFile` read from the server's disk."""
    path = (inp.get("contentFile") or "").strip()
    if path:
        if not os.path.isfile(path):
            fail("contentFile not found on the server: %s" % path)
        size = os.path.getsize(path)
        if size > CONTENTS_LIMIT:
            fail("contentFile is %d bytes; the Contents API caps a single file at %d. "
                 "Attach large files to a release with github-release instead."
                 % (size, CONTENTS_LIMIT))
        with open(path, "rb") as f:
            return f.read()
    raw = inp.get("content")
    if raw is None:
        fail("put-file needs either `content` (text) or `contentFile` (a path on the server)")
    data = raw.encode("utf-8")
    if len(data) > CONTENTS_LIMIT:
        fail("content is %d bytes; the Contents API caps a single file at %d bytes"
             % (len(data), CONTENTS_LIMIT))
    return data


def existing_sha(base, token, path, branch):
    """The blob sha of a file that is already there, or "" when it is not. "" means create."""
    url = "%s/%s" % (base, urllib.parse.quote(path))
    if branch:
        url += "?ref=" + urllib.parse.quote(branch)
    status, body = request("GET", url, token)
    if status == 404:
        return ""
    if status >= 400:
        fail("could not check whether %s already exists: HTTP %d%s" % (path, status, hint_for(status)))
    try:
        parsed = json.loads(body)
    except Exception:
        return ""
    if isinstance(parsed, list):
        fail("`path` is a directory, not a file: %s" % path)
    return parsed.get("sha") or ""


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
    path = (inp.get("path") or "").strip().lstrip("/")
    branch = (inp.get("branch") or "").strip()
    message = (inp.get("message") or "").strip()

    if op not in ("put-file", "get-file", "delete-file", "list-dir"):
        fail("unknown operation '%s'. one of: put-file, get-file, delete-file, list-dir" % op)
    for name, value in (("owner", owner), ("repo", repo)):
        if not value:
            fail("'%s' is required" % name)
    if op != "list-dir" and not path:
        fail("'path' is required for %s — the file path inside the repository" % op)

    base = "%s/repos/%s/%s/contents" % (API_BASE, urllib.parse.quote(owner), urllib.parse.quote(repo))

    if op == "get-file":
        url = "%s/%s" % (base, urllib.parse.quote(path))
        if branch:
            url += "?ref=" + urllib.parse.quote(branch)
        status, body = request("GET", url, token)
        text = ""
        sha = ""
        if 200 <= status < 300:
            try:
                parsed = json.loads(body)
                sha = parsed.get("sha", "")
                if parsed.get("encoding") == "base64":
                    text = base64.b64decode(parsed.get("content", "")).decode("utf-8", "replace")
            except Exception:
                pass
        emit(text or body, status, None if status < 400 else "HTTP %d%s" % (status, hint_for(status)), sha=sha)
        sys.exit(0 if status < 400 else 1)

    if op == "list-dir":
        url = base + ("/" + urllib.parse.quote(path) if path else "")
        if branch:
            url += "?ref=" + urllib.parse.quote(branch)
        status, body = request("GET", url, token)
        emit(body, status, None if status < 400 else "HTTP %d%s" % (status, hint_for(status)))
        sys.exit(0 if status < 400 else 1)

    # put-file and delete-file both write, and both need a commit message.
    if not message:
        message = ("Update %s via InTouch" if op == "put-file" else "Delete %s via InTouch") % path

    if op == "put-file":
        payload = {
            "message": message,
            "content": base64.b64encode(content_bytes(inp)).decode("ascii"),
        }
        if branch:
            payload["branch"] = branch
        sha = (inp.get("sha") or "").strip() or existing_sha(base, token, path, branch)
        if sha:
            payload["sha"] = sha
        status, body = request("PUT", "%s/%s" % (base, urllib.parse.quote(path)), token, payload)
        new_sha = ""
        html_url = ""
        if 200 <= status < 300:
            try:
                parsed = json.loads(body)
                new_sha = (parsed.get("content") or {}).get("sha", "")
                html_url = (parsed.get("content") or {}).get("html_url", "")
            except Exception:
                pass
        emit(body, status, None if status < 400 else "HTTP %d%s" % (status, hint_for(status)),
             sha=new_sha, url=html_url, action=("updated" if sha else "created"))
        sys.exit(0 if status < 400 else 1)

    # delete-file
    sha = (inp.get("sha") or "").strip() or existing_sha(base, token, path, branch)
    if not sha:
        fail("%s does not exist on %s — nothing to delete" % (path, branch or "the default branch"))
    payload = {"message": message, "sha": sha}
    if branch:
        payload["branch"] = branch
    status, body = request("DELETE", "%s/%s" % (base, urllib.parse.quote(path)), token, payload)
    emit(body, status, None if status < 400 else "HTTP %d%s" % (status, hint_for(status)))
    sys.exit(0 if status < 400 else 1)


if __name__ == "__main__":
    main()
