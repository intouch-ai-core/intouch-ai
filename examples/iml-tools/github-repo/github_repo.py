#!/usr/bin/env python3
"""github-repo — administer GitHub repositories: create, describe, retire.

The operations nobody needs until the moment they do, and which are then genuinely awkward from
a script: standing up a repository, setting its description and topics so it is findable, and
deleting one that should not exist.

InTouch tool convention: the script receives the path to a context JSON file as sys.argv[1],
reads ctx["input"] and ctx["credential"], and prints one JSON object whose keys become this
step's outputs.

The token comes from the BOUND CREDENTIAL and only from there.

Grounded in https://docs.github.com/en/rest/repos :
  create-repo  POST   /orgs/{org}/repos   or   /user/repos
  get-repo     GET    /repos/{owner}/{repo}
  update-repo  PATCH  /repos/{owner}/{repo}
  set-topics   PUT    /repos/{owner}/{repo}/topics     {names: [...]}
  list-repos   GET    /orgs/{org}/repos   or   /users/{owner}/repos
  delete-repo  DELETE /repos/{owner}/{repo}

SCOPES ARE NOT ONE THING. Creating a repository needs `repo` (classic) or Administration: write
(fine-grained). DELETING one needs `delete_repo` as well — a separate scope that `gh auth login`
does not grant by default, precisely because deletion cannot be undone. A token that creates
happily will still answer 403 on delete, and this tool says so rather than leaving you guessing.
"""
import json
import sys
import urllib.error
import urllib.parse
import urllib.request

API_BASE = "https://api.github.com"
API_VERSION = "2022-11-28"
MAX_BODY = 5 * 1024 * 1024
TIMEOUT = 60


# Every key named in tool.iml's `publish` block must be present on EVERY call. A publish
# reference to a key this run did not emit fails the whole task — even when the HTTP call
# succeeded — so the optional ones are declared here and default to empty.
PUBLISHED = ('fullName', 'url')


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
         "(credentialName on the task). Create the token at https://github.com/settings/tokens — "
         "Administration: write to create or edit repositories, and the delete_repo scope to delete one.")


def request(method, url, token, payload=None):
    """One authenticated GitHub call. Returns (status, body_text). Non-2xx is returned, not raised."""
    data = json.dumps(payload).encode("utf-8") if payload is not None else None
    req = urllib.request.Request(url, data=data, method=method)
    req.add_header("Authorization", "Bearer " + token)
    req.add_header("Accept", "application/vnd.github+json")
    req.add_header("X-GitHub-Api-Version", API_VERSION)
    req.add_header("User-Agent", "InTouch-AI/github-repo")
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


def hint_for(status, op):
    if status == 401:
        return " — the token was rejected; check it at https://github.com/settings/tokens"
    if status == 403:
        if op == "delete-repo":
            return (" — deleting needs the delete_repo scope, which is separate from repo and is NOT "
                    "granted by default. Add it at https://github.com/settings/tokens, or delete from "
                    "the repository's Settings page")
        return " — the token lacks Administration: write on this owner"
    if status == 404:
        return " — not found, or the token cannot see it (a fine-grained token only sees repositories it was scoped to)"
    if status == 422:
        return " — GitHub rejected the payload; usually a name that already exists or an invalid topic"
    return ""


def as_bool(value, default=False):
    text = (value or "").strip().lower()
    if not text:
        return default
    return text in ("1", "true", "yes", "on")


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
    org = (inp.get("org") or "").strip()
    owner = (inp.get("owner") or "").strip() or org
    repo = (inp.get("repo") or "").strip()
    known = ("create-repo", "get-repo", "update-repo", "set-topics", "list-repos", "delete-repo")
    if op not in known:
        fail("unknown operation '%s'. one of: %s" % (op, ", ".join(known)))

    if op == "create-repo":
        if not repo:
            fail("'repo' is required — the name of the repository to create")
        payload = {
            "name": repo,
            "description": (inp.get("description") or "").strip(),
            "homepage": (inp.get("homepage") or "").strip(),
            "private": as_bool(inp.get("private"), True),
            "auto_init": as_bool(inp.get("autoInit"), False),
        }
        url = ("%s/orgs/%s/repos" % (API_BASE, urllib.parse.quote(org))) if org else (API_BASE + "/user/repos")
        status, body = request("POST", url, token, payload)
        full = ""
        html = ""
        if 200 <= status < 300:
            try:
                parsed = json.loads(body)
                full = parsed.get("full_name") or ""
                html = parsed.get("html_url") or ""
            except Exception:
                pass
        emit(body, status, None if status < 400 else "HTTP %d%s" % (status, hint_for(status, op)),
             fullName=full, url=html)
        sys.exit(0 if status < 400 else 1)

    if op == "list-repos":
        if not owner:
            fail("'org' or 'owner' is required — whose repositories to list")
        # Try the org endpoint first; a personal account is not an org and answers 404 there.
        status, body = request("GET", "%s/orgs/%s/repos?per_page=100&sort=updated"
                               % (API_BASE, urllib.parse.quote(owner)), token)
        if status == 404:
            status, body = request("GET", "%s/users/%s/repos?per_page=100&sort=updated"
                                   % (API_BASE, urllib.parse.quote(owner)), token)
        emit(body, status, None if status < 400 else "HTTP %d%s" % (status, hint_for(status, op)))
        sys.exit(0 if status < 400 else 1)

    # Everything below acts on one existing repository.
    if not owner or not repo:
        fail("'owner' and 'repo' are both required for %s" % op)
    base = "%s/repos/%s/%s" % (API_BASE, urllib.parse.quote(owner), urllib.parse.quote(repo))

    if op == "get-repo":
        status, body = request("GET", base, token)
        emit(body, status, None if status < 400 else "HTTP %d%s" % (status, hint_for(status, op)))
        sys.exit(0 if status < 400 else 1)

    if op == "set-topics":
        raw = (inp.get("topics") or "").strip()
        if not raw:
            fail("'topics' is required for set-topics — a comma-separated list. "
                 "This REPLACES the repository's topics; pass them all.")
        names = [t.strip().lower() for t in raw.split(",") if t.strip()]
        status, body = request("PUT", base + "/topics", token, {"names": names})
        emit(body, status, None if status < 400 else "HTTP %d%s" % (status, hint_for(status, op)))
        sys.exit(0 if status < 400 else 1)

    if op == "update-repo":
        payload = {}
        for field, key in (("description", "description"), ("homepage", "homepage"),
                           ("defaultBranch", "default_branch"), ("newName", "name")):
            value = (inp.get(field) or "").strip()
            if value:
                payload[key] = value
        if (inp.get("private") or "").strip():
            payload["private"] = as_bool(inp.get("private"))
        if (inp.get("archived") or "").strip():
            payload["archived"] = as_bool(inp.get("archived"))
        if not payload:
            fail("update-repo needs at least one of: description, homepage, defaultBranch, "
                 "newName, private, archived")
        status, body = request("PATCH", base, token, payload)
        emit(body, status, None if status < 400 else "HTTP %d%s" % (status, hint_for(status, op)))
        sys.exit(0 if status < 400 else 1)

    # delete-repo — irreversible, and gated behind a confirmation the caller has to type.
    if (inp.get("confirm") or "").strip() != ("%s/%s" % (owner, repo)):
        fail("delete-repo will not run without `confirm` set to the exact repository being deleted: "
             "'%s/%s'. Deletion cannot be undone, and a typo in `repo` would otherwise delete "
             "whatever that typo happens to name." % (owner, repo))
    status, body = request("DELETE", base, token)
    emit(body or "%s/%s deleted" % (owner, repo), status,
         None if status < 400 else "HTTP %d%s" % (status, hint_for(status, op)))
    sys.exit(0 if status < 400 else 1)


if __name__ == "__main__":
    main()
