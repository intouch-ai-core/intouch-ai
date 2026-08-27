#!/usr/bin/env python3
"""github-insights — who is looking at your repository, and what they do next.

The same question a website asks of its access log, asked of a repository: how many people
arrived, where from, what they read, and how many went as far as cloning. GitHub keeps this for
the last 14 days and shows it under Insights → Traffic; this reads it, so it can land in a
scheduled digest instead of a browser tab nobody opens.

InTouch tool convention: the script receives the path to a context JSON file as sys.argv[1],
reads ctx["input"] and ctx["credential"], and prints one JSON object whose keys become this
step's outputs.

The token comes from the BOUND CREDENTIAL and only from there.

Grounded in https://docs.github.com/en/rest/metrics/traffic :
  views      GET /repos/{o}/{r}/traffic/views?per=day|week
  clones     GET /repos/{o}/{r}/traffic/clones?per=day|week
  referrers  GET /repos/{o}/{r}/traffic/popular/referrers
  paths      GET /repos/{o}/{r}/traffic/popular/paths
  summary    all four, plus stars/forks/watchers from /repos/{o}/{r}, as a readable report
  rate-limit GET /rate_limit

TWO LIMITS THAT ARE NOT BUGS. Traffic data requires PUSH access — a token that can read a public
repository still gets 403 here, because these numbers are the owner's, not the public's. And the
window is fourteen days, fixed: there is no date range parameter and nothing older is retained.
A trend longer than a fortnight has to be accumulated by running this on a schedule and keeping
the results.
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
BAR = 28          # width of the ASCII bars in the summary report


# Every key named in tool.iml's `publish` block must be present on EVERY call. A publish
# reference to a key this run did not emit fails the whole task — even when the HTTP call
# succeeded — so the optional ones are declared here and default to empty.
PUBLISHED = ('views', 'uniqueViews', 'clones', 'uniqueCloners')


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
         "(credentialName on the task). Traffic needs push access to the repository — create the "
         "token at https://github.com/settings/tokens with Administration: read and Contents: read.")


def request(method, url, token):
    """One authenticated GitHub call. Returns (status, body_text). Non-2xx is returned, not raised."""
    req = urllib.request.Request(url, method=method)
    req.add_header("Authorization", "Bearer " + token)
    req.add_header("Accept", "application/vnd.github+json")
    req.add_header("X-GitHub-Api-Version", API_VERSION)
    req.add_header("User-Agent", "InTouch-AI/github-insights")
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
        return (" — traffic data needs PUSH access to the repository. A token that can read a public "
                "repo still cannot read its traffic; these numbers belong to the owner")
    if status == 404:
        return " — repository not found, or the token cannot see it"
    return ""


def get_json(base, token, path):
    """Fetch and parse one endpoint. Returns (parsed_or_None, status)."""
    status, body = request("GET", base + path, token)
    if status >= 400:
        return None, status
    try:
        return json.loads(body), status
    except Exception:
        return None, status


def bars(rows, label_width=34):
    """Render (label, count) pairs as a padded ASCII bar chart, biggest first."""
    if not rows:
        return ["  (none in the last 14 days)"]
    top = max(count for _, count in rows)
    if not top:
        # Every bucket is zero. Rows of "0  0.0%  #" read as data; one sentence reads as the truth.
        return ["  (nothing in the last 14 days)"]
    total = sum(count for _, count in rows) or 1
    out = []
    for label, count in rows:
        text = label if len(label) <= label_width else label[:label_width - 1] + "…"
        out.append("  %-*s %6d  %5.1f%%  %s"
                   % (label_width, text, count, 100.0 * count / total, "#" * max(1, int(BAR * count / top))))
    return out


def summary_report(base, token, owner, repo, per):
    """Everything Insights → Traffic shows, as text you can put in an email."""
    lines = []
    lines.append("=" * 78)
    lines.append("  GITHUB TRAFFIC — %s/%s" % (owner, repo))
    lines.append("=" * 78)
    lines.append("  window: the last 14 days (GitHub keeps no more), grouped by %s" % per)
    lines.append("")

    repo_json, status = get_json(base, token, "")
    if repo_json is None and status >= 400:
        fail("could not read the repository: HTTP %d%s" % (status, hint_for(status)))
    if repo_json:
        lines.append("POPULARITY")
        lines.append("  stars %-6d forks %-6d watchers %-6d open issues %-6d"
                     % (repo_json.get("stargazers_count", 0), repo_json.get("forks_count", 0),
                        repo_json.get("subscribers_count", 0), repo_json.get("open_issues_count", 0)))
        if repo_json.get("private"):
            lines.append("  (this repository is PRIVATE — traffic will be near zero by definition)")
        lines.append("")

    totals = {}
    for name, path in (("views", "/traffic/views?per=" + per), ("clones", "/traffic/clones?per=" + per)):
        parsed, status = get_json(base, token, path)
        if parsed is None:
            lines.append("%s: unavailable (HTTP %d%s)" % (name.upper(), status, hint_for(status)))
            lines.append("")
            continue
        totals[name] = (parsed.get("count", 0), parsed.get("uniques", 0))
        lines.append("%s — %d total, %d unique" % (name.upper(), parsed.get("count", 0), parsed.get("uniques", 0)))
        rows = [((entry.get("timestamp") or "")[:10], entry.get("count", 0)) for entry in parsed.get(name, [])]
        lines.extend(bars(rows, label_width=12))
        lines.append("")

    for title, path, key in (("REFERRERS — where they came from", "/traffic/popular/referrers", "referrer"),
                             ("PATHS — what they opened", "/traffic/popular/paths", "path")):
        parsed, status = get_json(base, token, path)
        lines.append(title)
        if parsed is None:
            lines.append("  unavailable (HTTP %d%s)" % (status, hint_for(status)))
        else:
            lines.extend(bars([(entry.get(key, "?"), entry.get("count", 0)) for entry in parsed]))
        lines.append("")

    lines.append("Source: GitHub Traffic API. Counts include automated fetches; GitHub filters some")
    lines.append("but not all. Nothing older than 14 days exists — run this on a schedule to keep a trend.")
    views = totals.get("views", (0, 0))
    clones = totals.get("clones", (0, 0))
    return "\n".join(lines), views, clones


def main():
    if len(sys.argv) < 2:
        fail("context file path not provided")
    try:
        ctx = json.load(open(sys.argv[1]))
    except Exception as e:
        fail("cannot read context file: %s: %s" % (type(e).__name__, e))

    inp = ctx.get("input") or {}
    token = resolve_token(ctx.get("credential") or {})

    op = (inp.get("operation") or "summary").strip().lower()
    owner = (inp.get("owner") or "").strip()
    repo = (inp.get("repo") or "").strip()
    per = (inp.get("per") or "day").strip().lower()
    if per not in ("day", "week"):
        fail("`per` must be 'day' or 'week'")
    known = ("summary", "views", "clones", "referrers", "paths", "rate-limit")
    if op not in known:
        fail("unknown operation '%s'. one of: %s" % (op, ", ".join(known)))

    if op == "rate-limit":
        status, body = request("GET", API_BASE + "/rate_limit", token)
        emit(body, status, None if status < 400 else "HTTP %d%s" % (status, hint_for(status)))
        sys.exit(0 if status < 400 else 1)

    for name, value in (("owner", owner), ("repo", repo)):
        if not value:
            fail("'%s' is required" % name)
    base = "%s/repos/%s/%s" % (API_BASE, urllib.parse.quote(owner), urllib.parse.quote(repo))

    if op == "summary":
        report, views, clones = summary_report(base, token, owner, repo, per)
        emit(report, 200, None, views=views[0], uniqueViews=views[1],
             clones=clones[0], uniqueCloners=clones[1])
        sys.exit(0)

    path = {"views": "/traffic/views?per=" + per,
            "clones": "/traffic/clones?per=" + per,
            "referrers": "/traffic/popular/referrers",
            "paths": "/traffic/popular/paths"}[op]
    status, body = request("GET", base + path, token)
    emit(body, status, None if status < 400 else "HTTP %d%s" % (status, hint_for(status)))
    sys.exit(0 if status < 400 else 1)


if __name__ == "__main__":
    main()
