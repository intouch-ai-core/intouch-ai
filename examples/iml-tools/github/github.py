#!/usr/bin/env python3
"""GitHub — read and create GitHub data via the GitHub REST API.

InTouch tool convention: the script receives the path to a context JSON
file as sys.argv[1]. We read ctx["input"] and ctx["credential"], branch on
`operation`, build an authenticated request against https://api.github.com,
and print a single JSON object whose keys become this step's outputs.

The token comes from the BOUND CREDENTIAL and only from there. A token typed
into a workflow ends up in that workflow's exports, its activity log and any
repository the workflow is committed to — which is the opposite of what a
credential vault is for. Attach an API Key credential holding a GitHub
personal access token and the vault supplies it at execution.

Grounded in https://docs.github.com/en/rest :
  - Base URL:            https://api.github.com
  - Auth header:         Authorization: Bearer <token>
  - Accept:              application/vnd.github+json
  - X-GitHub-Api-Version: 2022-11-28

Operations (real, documented endpoints):
  list-issues    GET  /repos/{owner}/{repo}/issues?state=<state>
  get-issue      GET  /repos/{owner}/{repo}/issues/{issue_number}
  create-issue   POST /repos/{owner}/{repo}/issues          (title required)
  list-prs       GET  /repos/{owner}/{repo}/pulls?state=<state>
  list-releases  GET  /repos/{owner}/{repo}/releases
  get-repo       GET  /repos/{owner}/{repo}

Standard library only — never imports requests. Response bodies capped at 5 MB.
Non-2xx responses are returned (status + body) rather than raised, so the
caller can inspect them. Transport-level failures print an error envelope and
exit 1 so the YAML step is marked FAILED.
"""

import json
import sys
import urllib.error
import urllib.parse
import urllib.request

API_BASE = "https://api.github.com"
API_VERSION = "2022-11-28"
MAX_BODY_BYTES = 5 * 1024 * 1024  # 5 MB cap
DEFAULT_TIMEOUT = 60


def fail(msg, exit_code=1):
    """Emit an error envelope and exit — causes the YAML step to FAIL."""
    print(json.dumps({"result": "", "status": "0", "error": msg}))
    sys.exit(exit_code)


def resolve_token(cred):
    """The bound credential's token. Never logged, never echoed, never read from an input."""
    for field in ("apiKey", "key", "secret", "token"):
        value = cred.get(field)
        if isinstance(value, str) and value.strip():
            return value.strip()
    fail("No GitHub token. This tool needs an API Key credential attached to the task "
         "(credentialName). Create one under Credentials with a personal access token from "
         "https://github.com/settings/tokens, then name it on the task.")


def do_request(method, url, token, payload=None):
    """Perform an authenticated GitHub request. Returns (status, body_text)."""
    data = None
    if payload is not None:
        data = json.dumps(payload).encode("utf-8")

    req = urllib.request.Request(url, data=data, method=method)
    req.add_header("Authorization", f"Bearer {token}")
    req.add_header("Accept", "application/vnd.github+json")
    req.add_header("X-GitHub-Api-Version", API_VERSION)
    req.add_header("User-Agent", "InTouch/8.0 github-tool")
    if data is not None:
        req.add_header("Content-Type", "application/json")

    try:
        with urllib.request.urlopen(req, timeout=DEFAULT_TIMEOUT) as resp:
            status = resp.status
            body_bytes = resp.read(MAX_BODY_BYTES + 1)
    except urllib.error.HTTPError as e:
        # 4xx / 5xx — capture status + body, don't treat as transport failure
        status = e.code
        try:
            body_bytes = e.read(MAX_BODY_BYTES + 1)
        except Exception:
            body_bytes = b""
    except Exception as e:
        fail(f"Request failed: {type(e).__name__}: {e}")
        return  # unreachable

    if len(body_bytes) > MAX_BODY_BYTES:
        body_bytes = body_bytes[:MAX_BODY_BYTES]
    body_text = body_bytes.decode("utf-8", errors="replace")
    return status, body_text


def require(value, name):
    if not value:
        fail(f"'{name}' is required for this operation")
    return value


def main():
    if len(sys.argv) < 2:
        fail("Context file path not provided")

    with open(sys.argv[1]) as f:
        ctx = json.load(f)

    inp = ctx.get("input", {})
    cred = ctx.get("credential") or {}

    token = resolve_token(cred)

    operation = inp.get("operation", "").strip()
    if not operation:
        fail("'operation' is required")

    owner = inp.get("owner", "").strip()
    repo = inp.get("repo", "").strip()
    state = (inp.get("state", "open") or "open").strip()

    # All current operations are repo-scoped.
    require(owner, "owner")
    require(repo, "repo")
    base = f"{API_BASE}/repos/{urllib.parse.quote(owner)}/{urllib.parse.quote(repo)}"

    if operation == "list-issues":
        qs = urllib.parse.urlencode({"state": state, "per_page": "30"})
        status, body = do_request("GET", f"{base}/issues?{qs}", token)

    elif operation == "get-issue":
        num = require(inp.get("issueNumber", "").strip(), "issueNumber")
        status, body = do_request("GET", f"{base}/issues/{urllib.parse.quote(num)}", token)

    elif operation == "create-issue":
        title = require(inp.get("title", "").strip(), "title")
        payload = {"title": title}
        issue_body = inp.get("body", "").strip()
        if issue_body:
            payload["body"] = issue_body
        labels_raw = inp.get("labels", "").strip()
        if labels_raw:
            payload["labels"] = [l.strip() for l in labels_raw.split(",") if l.strip()]
        status, body = do_request("POST", f"{base}/issues", token, payload=payload)

    elif operation == "list-prs":
        qs = urllib.parse.urlencode({"state": state, "per_page": "30"})
        status, body = do_request("GET", f"{base}/pulls?{qs}", token)

    elif operation == "list-releases":
        qs = urllib.parse.urlencode({"per_page": "30"})
        status, body = do_request("GET", f"{base}/releases?{qs}", token)

    elif operation == "get-repo":
        status, body = do_request("GET", base, token)

    else:
        fail(
            "Unknown operation '%s'. Valid: list-issues, get-issue, "
            "create-issue, list-prs, list-releases, get-repo" % operation
        )
        return  # unreachable

    ok = 200 <= status < 300
    print(json.dumps({
        "result": body,
        "status": str(status),
        "ok": "true" if ok else "false",
        "operation": operation,
    }))
    # GitHub failures (non-2xx) are surfaced via status/ok but are not a
    # transport failure, so we exit 0 and let the caller branch on `status`.


if __name__ == "__main__":
    main()
