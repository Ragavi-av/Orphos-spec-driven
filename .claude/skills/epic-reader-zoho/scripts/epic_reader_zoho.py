#!/usr/bin/env python3
"""Epic Reader (Zoho Sprints) helper for the `epic-reader-zoho` Claude skill.

Stdlib only. Usage: python epic_reader_zoho.py <scan|read|create> ...
Exit: 0 ok | 2 gate rejected | 1 error | 64 usage

The approval gate is fail-closed: every check must pass, and `create` re-runs the
gate immediately before writing. People are reported by Zoho user id only.

NOTE: Zoho Sprints endpoint paths (config "api") and the response field names read in
`pick()` calls were written without access to live API docs. Verify with `read` against a
real epic and adjust config / aliases before trusting the gate.
"""
import base64
import json
import os
import re
import sys
import urllib.error
import urllib.parse
import urllib.request
from pathlib import Path

SKILL_DIR = Path(__file__).resolve().parent.parent
DEFAULT_CONFIG = SKILL_DIR / "epic-reader-zoho.config.json"
EXIT_OK, EXIT_ERROR, EXIT_REJECTED, EXIT_USAGE = 0, 1, 2, 64
USAGE = """usage: epic_reader_zoho.py <command> [options]
  scan   [--project ID]                        approved epics with gate verdicts
  read   <EPIC-ID>                             gate verdict (+ epic content if APPROVED)
  create <EPIC-ID> <stories.json> [--dry-run]  re-check gate, then create stories under the epic
options: --config <path>  (default $EPIC_READER_ZOHO_CONFIG, else skill folder)
env:     ZOHO_CLIENT_ID, ZOHO_CLIENT_SECRET, ZOHO_REFRESH_TOKEN, ZOHO_TEAM_ID, [ZOHO_DOMAIN=com]"""


class Fail(Exception):
    pass


# ---------------------------------------------------------------- helpers
def load_env():
    for p in (Path(".env"), Path(__file__).resolve().parent / ".env"):
        if p.exists():
            for line in p.read_text(encoding="utf-8").splitlines():
                line = line.strip()
                if line and not line.startswith("#") and "=" in line:
                    k, v = line.split("=", 1)
                    os.environ.setdefault(k.strip(), v.strip().strip("\"'"))


def pick(d, *names, default=None):
    """First present key among aliases (Zoho field names vary by API version)."""
    if isinstance(d, dict):
        for n in names:
            if d.get(n) not in (None, ""):
                return d[n]
    return default


def norm(s):
    return re.sub(r"\s+", " ", (s or "").strip().lower())


def html_to_text(s):
    if not s:
        return ""
    s = re.sub(r"(?i)<br\s*/?>|</p>|</li>|</h\d>", "\n", s)
    s = re.sub(r"(?i)<li[^>]*>", "- ", s)
    s = re.sub(r"<[^>]+>", "", s)
    s = (s.replace("&nbsp;", " ").replace("&amp;", "&").replace("&lt;", "<")
         .replace("&gt;", ">").replace("&quot;", '"'))
    return re.sub(r"\n{3,}", "\n\n", s).strip()


def decode_epics(resp):
    """Zoho returns {"epicJObj": {id: [values...]}, "epic_prop": {field: index}} - decode to dicts."""
    if not isinstance(resp, dict) or "epicJObj" not in resp:
        return []
    props = resp.get("epic_prop") or {}
    out = []
    for eid, arr in (resp["epicJObj"] or {}).items():
        row = {"epicId": str(eid)}
        for field, idx in props.items():
            if isinstance(arr, list) and isinstance(idx, int) and idx < len(arr):
                row[field] = arr[idx]
        out.append(row)
    return out


def as_list(resp, *keys):
    """Zoho list responses are often {"<key>": [...]} or {"<key>": {id: {...}}}."""
    if isinstance(resp, list):
        return resp
    for k in keys:
        v = resp.get(k) if isinstance(resp, dict) else None
        if isinstance(v, list):
            return v
        if isinstance(v, dict):
            return list(v.values())
    return []


# ---------------------------------------------------------------- config
class Config:
    def __init__(self, path):
        if not path.exists():
            raise Fail(f"config not found at {path} - copy epic-reader-zoho.config.example.json "
                       "to epic-reader-zoho.config.json and fill it in")
        m = json.loads(path.read_text(encoding="utf-8"))
        self.project_id = m.get("projectId") or None
        self.item_type = m.get("storyItemType") or "Story"
        # Zoho epics carry approval in a custom pick-list field (e.g. UDF_PKL1) whose values are option ids.
        self.approval_field = m.get("approvalField") or None
        vals = m.get("approvedValues") if self.approval_field else m.get("approvedStatuses")
        self.approved = [str(s) for s in (vals or []) if s] or ["Approved"]
        self.approvers = set(map(str, (m.get("authorizedApprovers") or {}).get("userIds", [])))
        self.gate_mode = m.get("gateMode", "strict")
        if self.gate_mode not in ("strict", "simple"):
            raise Fail("gateMode must be 'strict' or 'simple'")
        self.no_self = m.get("forbidSelfApproval", True)
        self.no_edits = m.get("rejectIfEditedAfterApproval", True)
        self.protected = m.get("protectedFields") or ["name", "description"]
        self.backlog_sprint = str(m.get("backlogSprintId") or "")
        self.tags = m.get("defaultTags", [])
        self.comment = m.get("commentOnEpic", True)
        self.max_stories = int(m.get("maxStoriesPerRun", 25))
        self.scan_limit = int(m.get("scanLimit", 50))
        self.api = m.get("api") or {}

    def is_approved_status(self, s):
        return bool(s) and any(norm(a) == norm(s) for a in self.approved)


# ---------------------------------------------------------------- Zoho client
class Zoho:
    def __init__(self):
        load_env()
        need = ["ZOHO_CLIENT_ID", "ZOHO_CLIENT_SECRET", "ZOHO_REFRESH_TOKEN", "ZOHO_TEAM_ID"]
        missing = [k for k in need if not os.environ.get(k)]
        if missing:
            raise Fail(f"missing environment variables or .env entries: {missing}")
        self.domain = os.environ.get("ZOHO_DOMAIN", "com")
        self.team = os.environ["ZOHO_TEAM_ID"]
        self.base = f"https://sprintsapi.zoho.{self.domain}/zsapi"
        self._token = None

    def token(self):
        if not self._token:
            body = urllib.parse.urlencode({
                "grant_type": "refresh_token",
                "client_id": os.environ["ZOHO_CLIENT_ID"],
                "client_secret": os.environ["ZOHO_CLIENT_SECRET"],
                "refresh_token": os.environ["ZOHO_REFRESH_TOKEN"],
            }).encode()
            req = urllib.request.Request(f"https://accounts.zoho.{self.domain}/oauth/v2/token", data=body)
            data = self._open(req, "oauth token")
            if "access_token" not in data:
                raise Fail("Zoho OAuth refresh failed - check client id/secret/refresh token and ZOHO_DOMAIN")
            self._token = data["access_token"]
        return self._token

    def _open(self, req, label):
        try:
            with urllib.request.urlopen(req, timeout=30) as r:
                txt = r.read().decode("utf-8")
                return json.loads(txt) if txt.strip() else {}
        except urllib.error.HTTPError as e:
            code = e.code
            if code == 401:
                raise Fail("Zoho authentication failed (HTTP 401) - token/scopes")
            if code == 403:
                raise Fail(f"Zoho permission denied (HTTP 403) on {label}")
            if code == 404:
                raise Fail(f"not found, or wrong endpoint path (HTTP 404): {label}")
            raise Fail(f"HTTP {code} on {label}: {e.read().decode('utf-8', 'replace')[:300]}")
        except urllib.error.URLError as e:
            raise Fail(f"network error on {label}: {e.reason}")

    def call(self, path_key, cfg, method="GET", params=None, form=None, **ids):
        tmpl = cfg.api.get(path_key)
        if not tmpl:
            raise Fail(f"api.{path_key} missing in config")
        path = tmpl.format(team=self.team, **ids)
        url = self.base + path + (("?" + urllib.parse.urlencode(params)) if params else "")
        data = urllib.parse.urlencode(form).encode() if form is not None else None
        req = urllib.request.Request(url, data=data, method=method, headers={
            "Authorization": f"Zoho-oauthtoken {self.token()}", "Accept": "application/json"})
        return self._open(req, path)

    def paged(self, path_key, cfg, keys, limit, decode=None, **ids):
        out, index = [], 1
        while len(out) < limit:
            resp = self.call(path_key, cfg, params={"action": "data", "index": index, "range": 100}, **ids)
            page = decode(resp) if decode else as_list(resp, *keys)
            out += page
            if len(page) < 100:
                break
            index += 100
        return out[:limit]


# ---------------------------------------------------------------- gate
def epic_fields(e, cfg):
    return {
        "name": str(e.get("epicName") or "").strip(),
        "description": e.get("description") or "",
        "status": str(e.get(cfg.approval_field or "epicStatus") or ""),
        "owners": {str(v) for v in (e.get("createdBy"), e.get("owner")) if v},
    }


def user_ids(raw_resp, uid):
    """A Sprints user id plus its Zoho account id (ZUID), so approvers may be listed in either form."""
    ids = {str(uid)} if uid else set()
    zmap = (raw_resp or {}).get("zsuserIdvsZUID") or {}
    if str(uid) in zmap:
        ids.add(str(zmap[str(uid)]))
    return ids


def evaluate_gate(z, cfg, project, epic_id):
    checks, approval = [], {}

    def check(name, ok, detail):
        checks.append({"name": name, "pass": bool(ok), "detail": detail})

    raw = z.call("epicGet", cfg, params={"action": "details"}, project=project, epic=epic_id)
    epic = next((e for e in decode_epics(raw) if e["epicId"] == str(epic_id)), {})
    f = epic_fields(epic, cfg)
    check("epicFound", bool(f["name"]), "epic readable" if f["name"] else "epic has no name - wrong id or response shape")

    check("currentStatusApproved", cfg.is_approved_status(f["status"]),
          f"current {cfg.approval_field or 'status'} value '{f['status']}'; approved values {cfg.approved}")

    if cfg.gate_mode == "simple":
        # Reduced gate: Zoho exposes no change history here, so approver, self-approval and
        # edit-after-approval cannot be verified. Only the current value and the creator are checked.
        creator = str(epic.get("createdBy") or "")
        creator_ok = bool(user_ids(raw, creator) & cfg.approvers)
        check("creatorAuthorized", bool(creator) and creator_ok,
              "authorizedApprovers.userIds is empty - nobody is authorized (fail closed)" if not cfg.approvers
              else "epic has no creator" if not creator
              else "epic creator is in authorizedApprovers" if creator_ok
              else "epic creator is not in authorizedApprovers.userIds")
        approved = all(c["pass"] for c in checks)
        return {"approved": approved, "epic": f, "raw": epic,
                "json": {"verdict": "APPROVED" if approved else "REJECTED", "gateMode": "simple",
                         "notVerified": ["approver", "selfApproval", "editedAfterApproval"],
                         "approval": approval, "checks": checks}}

    # History: who moved the epic to an approved status, and later edits. Fail closed if unavailable.
    events, hist_err = [], None
    try:
        hist = z.call("epicHistory", cfg, project=project, epic=epic_id)
        events = as_list(hist, "timeline", "history", "activities", "audit", "logs")
    except Fail as e:
        hist_err = str(e)
    norm_events = []
    for ev in events:
        norm_events.append({
            "time": str(pick(ev, "time", "timestamp", "createdTime", "date", default="")),
            "user": str(pick(ev, "userId", "user", "actor", "modifiedBy", "createdBy", default="")),
            "field": norm(str(pick(ev, "field", "fieldName", "property", default=""))),
            "to": str(pick(ev, "newValue", "to", "new", "toValue", default="")),
        })
    norm_events.sort(key=lambda x: x["time"])
    idx = -1
    for i, ev in enumerate(norm_events):
        if "status" in ev["field"] and cfg.is_approved_status(ev["to"]):
            idx = i
    ev = norm_events[idx] if idx >= 0 else None
    approver = ev["user"] if ev and ev["user"] else None
    check("approvalTransitionFound", approver is not None,
          f"history unavailable: {hist_err}" if hist_err
          else "no status change into an approved status in the history" if not ev
          else "approval transition has no user" if not approver else f"approved at {ev['time']}")
    if ev:
        approval.update(approvedAt=ev["time"], approverUserId=approver)

    ok = bool(approver) and bool(user_ids(raw, approver) & cfg.approvers)
    check("approverAuthorized", ok,
          "authorizedApprovers.userIds is empty - nobody is authorized (fail closed)" if not cfg.approvers
          else "no approver to check" if not approver
          else "approver is in authorizedApprovers" if ok else "approver is not in authorizedApprovers.userIds")

    if cfg.no_self:
        self_ = bool(approver) and approver in f["owners"]
        check("notSelfApproved", bool(approver) and not self_,
              "no approver to check" if not approver
              else "approver is the epic's creator/owner" if self_ else "approver differs from creator/owner")

    if cfg.no_edits and idx >= 0:
        edits = [f"{e['field']} @ {e['time']}" for e in norm_events[idx + 1:]
                 if any(p in e["field"] for p in map(norm, cfg.protected))]
        check("unchangedSinceApproval", not edits,
              f"no edits to {cfg.protected} after approval" if not edits
              else f"edited after approval (re-approval needed): {edits}")

    approved = all(c["pass"] for c in checks)
    return {"approved": approved, "epic": f, "raw": epic,
            "json": {"verdict": "APPROVED" if approved else "REJECTED", "approval": approval, "checks": checks}}


def children(z, cfg, project, epic_id):
    """Items linked to the epic, via the project-level items list filtered on the epic."""
    flt = json.dumps({"I-epic": [str(epic_id)], "queryType": 1, "jsontmpl": "item_default"}, separators=(",", ":"))
    items, index = [], 1
    while True:
        resp = z.call("epicItems", cfg, params={"action": "data", "index": index, "range": 100, "filter": flt},
                      project=project, epic=epic_id)
        if resp.get("itemJObj") and resp.get("item_prop"):      # same compact shape as epics
            props = resp["item_prop"]
            page = [{"itemId": str(i), **{f: a[ix] for f, ix in props.items()
                                          if isinstance(ix, int) and ix < len(a)}}
                    for i, a in resp["itemJObj"].items()]
        else:
            page = as_list(resp, "item", "items", "epicItems")
        if not page and resp.get("itemIds"):
            raise Fail("items exist under the epic but their details were not returned - cannot de-duplicate")
        items += page
        if not resp.get("next") or not page:
            break
        index += 100
    return [{"id": str(pick(i, "itemId", "id", "itemNo", default="")),
             "name": pick(i, "name", "title", default=""),
             "status": pick(i, "statusName", "status", default="")} for i in items]


# ---------------------------------------------------------------- commands
def safe_children(z, cfg, project, epic_id):
    try:
        items = children(z, cfg, project, epic_id)
        return {"items": items, "count": len(items), "error": None}
    except Fail as e:
        return {"items": [], "count": None, "error": str(e)}


def need_project(cfg, override=None):
    p = override or cfg.project_id
    if not p:
        raise Fail("projectId missing - set it in config or pass --project")
    return p


def epics_with_approval(z, cfg, project):
    """List epics, then fetch details (the list omits custom fields such as UDF_PKL1)."""
    out = []
    for e in z.paged("epicList", cfg, (), cfg.scan_limit, decode=decode_epics, project=project):
        if cfg.approval_field and cfg.approval_field not in e:
            d = decode_epics(z.call("epicGet", cfg, params={"action": "details"}, project=project, epic=e["epicId"]))
            e = {**e, **(d[0] if d else {})}
        out.append(e)
    return out


def cmd_scan(z, cfg, project):
    project = need_project(cfg, project)
    rows = []
    for e in epics_with_approval(z, cfg, project):
        f = epic_fields(e, cfg)
        if not cfg.is_approved_status(f["status"]):
            continue
        eid = e["epicId"]
        g = evaluate_gate(z, cfg, project, eid)
        rows.append({"id": eid, "name": f["name"], "status": f["status"], "gate": g["json"]["verdict"],
                     "failedChecks": [c["name"] for c in g["json"]["checks"] if not c["pass"]],
                     "existingChildItems": safe_children(z, cfg, project, eid)["count"]})
    print(json.dumps({"project": project, "epics": rows}, indent=2))
    return EXIT_OK


def cmd_read(z, cfg, epic_id, project):
    project = need_project(cfg, project)
    g = evaluate_gate(z, cfg, project, epic_id)
    out = {"epicId": epic_id, "gate": g["json"]}
    if g["approved"]:  # content released only for an approved epic
        out["epic"] = {"name": g["epic"]["name"], "description": html_to_text(g["epic"]["description"])}
        ch = safe_children(z, cfg, project, epic_id)
        out["existingChildItems"] = ch["items"]
        if ch["error"]:
            out["existingChildItemsError"] = ch["error"]
    print(json.dumps(out, indent=2))
    return EXIT_OK if g["approved"] else EXIT_REJECTED


def cmd_create(z, cfg, epic_id, path, dry, project):
    project = need_project(cfg, project)
    doc = json.loads(Path(path).read_text(encoding="utf-8"))
    if doc.get("epicKey") and str(doc["epicKey"]).lower() != epic_id.lower() and \
            str(doc.get("epicId", "")).lower() != epic_id.lower():
        raise Fail(f"stories file is for {doc.get('epicKey')}, not {epic_id}")
    stories = doc.get("stories") or []
    if not stories:
        raise Fail("stories file has no stories")
    if len(stories) > cfg.max_stories:
        raise Fail(f"{len(stories)} stories exceeds maxStoriesPerRun={cfg.max_stories}")

    g = evaluate_gate(z, cfg, project, epic_id)           # re-check right before writing
    out = {"epicId": epic_id, "dryRun": dry, "gate": g["json"]}
    if not g["approved"]:
        out["created"] = []
        print(json.dumps(out, indent=2))
        return EXIT_REJECTED

    seen = {norm(c["name"]) for c in children(z, cfg, project, epic_id)}  # raises if lookup fails: no dedupe, no create
    if not cfg.backlog_sprint:
        raise Fail("backlogSprintId missing in config - the item create call needs the sprint/backlog id")
    type_id, priorities = lookup_ids(z, cfg, project)
    created, skipped, failed = [], [], []
    for s in stories:
        name = (s.get("summary") or "").strip()[:255]
        if not name:
            failed.append({"summary": "", "error": "missing summary"})
            continue
        if norm(name) in seen:
            skipped.append({"summary": name, "reason": "an item with this name already exists under the epic"})
            continue
        seen.add(norm(name))
        form = {"name": name, "projitemtypeid": type_id, "epicid": epic_id,
                "description": story_html(s, epic_id, list(cfg.tags) + list(s.get("labels", [])))}
        pri = priorities.get(norm(s.get("priority") or ""))
        if s.get("priority") and not pri:
            failed.append({"summary": name, "error": f"unknown priority '{s['priority']}'; known: {sorted(priorities)}"})
            continue
        if pri:
            form["projpriorityid"] = pri
        if isinstance(s.get("storyPoints"), (int, float)):
            form["point"] = s["storyPoints"]
        if dry:
            created.append({"summary": name, "payload": form})
            continue
        try:
            res = z.call("itemCreate", cfg, method="POST", params=form, project=project, sprint=cfg.backlog_sprint)
            created.append({"id": str(find_id(res)), "summary": name})
        except Fail as e:
            failed.append({"summary": name, "error": str(e)})

    if not dry and cfg.comment and created:
        ids = ", ".join(c["id"] for c in created if c["id"])
        try:
            z.call("itemComment", cfg, method="POST", project=project, epic=epic_id,
                   form={"comment": f"Epic Reader created {len(created)} user stories from this approved epic: {ids}."})
        except Fail as e:
            out["commentError"] = str(e)
    out.update(created=created, skipped=skipped, failed=failed)
    print(json.dumps(out, indent=2))
    return EXIT_OK if not failed else EXIT_ERROR


def lookup_ids(z, cfg, project):
    """Project item-type id for cfg.item_type, and {priority name: project priority id}."""
    r = z.call("itemTypes", cfg, params={"action": "data"}, project=project)
    p = r.get("projItemType_prop") or {}
    type_id = next((i for i, a in (r.get("projItemTypeJObj") or {}).items()
                    if norm(str(a[p["itemTypeName"]])) == norm(cfg.item_type)), None)
    if not type_id:
        raise Fail(f"item type '{cfg.item_type}' not found in the project")
    r = z.call("priorities", cfg, params={"action": "data"}, project=project)
    p = r.get("projPriority_prop") or {}
    pri = {norm(str(a[p["priorityName"]])): i for i, a in (r.get("projPriorityJObj") or {}).items()}
    return type_id, pri


def find_id(res):
    if isinstance(res, dict):
        v = pick(res, "itemId", "id", "itemNo")
        if v:
            return v
        for x in res.values():
            r = find_id(x)
            if r:
                return r
    return ""


def story_html(s, epic_id, labels=()):
    esc = lambda t: (str(t).replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;"))
    parts = []
    if s.get("userStory"):
        parts.append(f"<p>{esc(s['userStory'])}</p>")
    for p in re.split(r"\n\s*\n", s.get("description") or ""):
        if p.strip():
            parts.append(f"<p>{esc(p.strip())}</p>")
    if s.get("acceptanceCriteria"):
        parts.append("<h3>Acceptance criteria</h3><ul>" +
                     "".join(f"<li>{esc(a)}</li>" for a in s["acceptanceCriteria"]) + "</ul>")
    if s.get("notes"):
        parts.append(f"<h3>Notes</h3><p>{esc(s['notes'])}</p>")
    if labels:
        parts.append(f"<p>Labels: {esc(', '.join(dict.fromkeys(labels)))}</p>")
    parts.append(f"<p>Generated by Epic Reader (Zoho) from approved epic {esc(epic_id)}.</p>")
    return "".join(parts)


def main(argv):
    pos, opt, i = [], {}, 0
    while i < len(argv):
        a = argv[i]
        if a == "--dry-run":
            opt["dry-run"] = True
        elif a.startswith("--"):
            if i + 1 >= len(argv):
                return usage(f"{a} needs a value")
            opt[a[2:]] = argv[i + 1]
            i += 1
        else:
            pos.append(a)
        i += 1
    if not pos:
        return usage("missing command")
    cfg = Config(Path(opt.get("config") or os.environ.get("EPIC_READER_ZOHO_CONFIG") or DEFAULT_CONFIG))
    z = Zoho()
    cmd = pos[0]
    if cmd == "scan":
        return cmd_scan(z, cfg, opt.get("project"))
    if cmd == "read" and len(pos) == 2:
        return cmd_read(z, cfg, pos[1], opt.get("project"))
    if cmd == "create" and len(pos) == 3:
        return cmd_create(z, cfg, pos[1], pos[2], bool(opt.get("dry-run")), opt.get("project"))
    return usage(f"bad arguments for {cmd}")


def usage(msg):
    print(f"usage error: {msg}\n{USAGE}", file=sys.stderr)
    return EXIT_USAGE


if __name__ == "__main__":
    try:
        sys.exit(main(sys.argv[1:]))
    except Fail as e:
        print(f"error: {e}", file=sys.stderr)
        sys.exit(EXIT_ERROR)
