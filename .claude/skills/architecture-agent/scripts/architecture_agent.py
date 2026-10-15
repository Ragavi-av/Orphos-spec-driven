#!/usr/bin/env python3
"""
High-Level Architecture agent helper for the architecture-agent Claude skill.

Talks to Jira Cloud REST v3 with API-token Basic Auth from
JIRA_URL, JIRA_EMAIL, JIRA_TOKEN. Credentials are never printed.

Commands:
  scan   — approved tasks with gate verdict and existing architecture attachment info
  read   — gate verdict, task content, story context, sibling tasks, acceptance criteria
  attach — gate check, then upload a file as an attachment on the task

Output never carries e-mail addresses or display names — people are identified
by Jira accountId only.
"""
from __future__ import annotations

import argparse
import base64
import json
import os
import re
import sys
from pathlib import Path
from urllib.parse import quote

import requests

EXIT_OK, EXIT_ERROR, EXIT_REJECTED, EXIT_USAGE = 0, 1, 2, 64

# ── config ───────────────────────────────────────────────────────────────


class Config:
    def __init__(self, data: dict):
        self.project_key = _blank_to_none(data.get("projectKey"))
        self.task_issue_types = _strings(data.get("taskIssueTypes")) or ["Task", "Sub-task"]
        self.approved_statuses = _strings(data.get("approvedStatuses")) or ["Approved"]
        self.story_approved_statuses = (
            _strings(data.get("storyApprovedStatuses")) or ["Approved", "In Progress"]
        )
        aa = data.get("authorizedApprovers") or {}
        self.approver_account_ids: list[str] = _strings(aa.get("accountIds"))
        self.approver_groups: list[str] = _strings(aa.get("groups"))
        self.approver_field = _blank_to_none(data.get("approverField"))
        self.approver_field_must_match_transition: bool = data.get(
            "approverFieldMustMatchTransition", True
        )
        self.protected_fields = _strings(data.get("protectedFields")) or ["summary", "description"]
        self.forbid_self_approval: bool = data.get("forbidSelfApproval", True)
        self.reject_if_edited_after_approval: bool = data.get("rejectIfEditedAfterApproval", True)
        self.require_approved_story: bool = data.get("requireApprovedStory", True)
        self.attachment_filename: str = (
            _blank_to_none(data.get("attachmentFilename")) or "architecture-{taskKey}.md"
        )
        self.on_existing_attachment: str = (
            _blank_to_none(data.get("onExistingAttachment")) or "replace"
        ).lower()
        if self.on_existing_attachment not in ("replace", "fail"):
            raise SystemExit(
                f"onExistingAttachment must be 'replace' or 'fail', got '{self.on_existing_attachment}'"
            )
        self.comment_on_task: bool = data.get("commentOnTask", True)
        self.scan_limit: int = int(data.get("scanLimit", 50))
        self._group_cache: dict[str, set[str]] = {}

    @classmethod
    def load(cls, path: Path) -> Config:
        if not path.exists():
            raise SystemExit(
                f"config not found at {path}"
                " - copy architecture-agent.config.example.json"
                " to architecture-agent.config.json and fill it in"
            )
        return cls(json.loads(path.read_text(encoding="utf-8-sig")))

    def is_approved_status(self, status: str | None) -> bool:
        return _matches_any(self.approved_statuses, status)

    def has_any_approver(self) -> bool:
        return bool(self.approver_account_ids or self.approver_groups)

    def is_authorized(self, jira: Jira, account_id: str) -> bool:
        if account_id in self.approver_account_ids:
            return True
        if not self.approver_groups:
            return False
        if account_id not in self._group_cache:
            groups: set[str] = set()
            for g in jira.get(f"/rest/api/3/user/groups?accountId={quote(account_id)}"):
                name = g.get("name")
                if name:
                    groups.add(name.lower())
            self._group_cache[account_id] = groups
        return any(g.lower() in self._group_cache[account_id] for g in self.approver_groups)


# ── Jira REST client ─────────────────────────────────────────────────────


class JiraError(Exception):
    pass


class Jira:
    def __init__(self, base_url: str, auth_header: str):
        self.base = base_url.rstrip("/")
        self.session = requests.Session()
        self.session.headers.update({"Authorization": auth_header, "Accept": "application/json"})

    @classmethod
    def from_env(cls) -> Jira:
        url = os.environ.get("JIRA_URL")
        email = os.environ.get("JIRA_EMAIL")
        token = os.environ.get("JIRA_TOKEN")
        if not all((url, email, token)):
            env: dict[str, str] = {}
            for p in [
                ".env",
                ".claude/skills/architecture-agent/scripts/.env",
                ".claude/skills/test-case-agent/scripts/.env",
                ".claude/skills/user-story-agent/scripts/.env",
            ]:
                try:
                    for k, v in _load_env_file(Path(p)).items():
                        env.setdefault(k, v)
                except OSError:
                    pass
            url = url or env.get("JIRA_URL")
            email = email or env.get("JIRA_EMAIL")
            token = token or env.get("JIRA_TOKEN")
        missing = [k for k, v in [("JIRA_URL", url), ("JIRA_EMAIL", email), ("JIRA_TOKEN", token)]
                    if not v]
        if missing:
            raise SystemExit(f"missing environment variables or .env file: {missing}")
        raw = f"{email.strip()}:{token.strip()}"
        auth = "Basic " + base64.b64encode(raw.encode()).decode()
        return cls(url.strip(), auth)

    def _handle(self, resp: requests.Response, path: str):
        sc = resp.status_code
        short = path.split("?")[0]
        if sc == 401:
            raise JiraError("Jira authentication failed (HTTP 401) - check JIRA_EMAIL / JIRA_TOKEN")
        if sc == 403:
            raise JiraError(f"Jira permission denied (HTTP 403) on {short}")
        if sc == 404:
            raise JiraError(f"not found, or no permission to see it (HTTP 404): {short}")
        if sc // 100 != 2:
            raise JiraError(f"HTTP {sc} on {short}: {resp.text[:300]}")
        if not resp.text.strip():
            return {}
        return resp.json()

    def get(self, path: str):
        return self._handle(self.session.get(self.base + path, timeout=30), path)

    def post(self, path: str, body):
        return self._handle(self.session.post(self.base + path, json=body, timeout=30), path)

    def delete(self, path: str):
        return self._handle(self.session.delete(self.base + path, timeout=30), path)

    def post_multipart(self, path: str, filename: str, content_type: str, data: bytes):
        safe = filename.replace('"', "'").replace("\r", "").replace("\n", "")
        resp = self.session.post(
            self.base + path,
            files={"file": (safe, data, content_type)},
            headers={"X-Atlassian-Token": "no-check"},
            timeout=60,
        )
        return self._handle(resp, path)

    def changelog(self, key: str) -> list[dict]:
        entries: list[dict] = []
        start_at = 0
        while True:
            page = self.get(
                f"/rest/api/3/issue/{quote(key)}/changelog?startAt={start_at}&maxResults=100"
            )
            values = page.get("values", [])
            entries.extend(values)
            if not values or page.get("isLast", True):
                break
            start_at += len(values)
        return entries

    def search(self, jql: str, fields: list[str], limit: int) -> list[dict]:
        results: list[dict] = []
        token = None
        while len(results) < limit:
            path = (
                f"/rest/api/3/search/jql?jql={quote(jql)}"
                f"&fields={quote(','.join(fields))}&maxResults={min(100, limit - len(results))}"
            )
            if token:
                path += f"&nextPageToken={quote(token)}"
            page = self.get(path)
            results.extend(page.get("issues", []))
            token = page.get("nextPageToken")
            if not token or page.get("isLast", True):
                break
        return results


# ── ADF helpers ───────────────────────────────────────────────────────────


def adf_doc(content: list) -> dict:
    return {"type": "doc", "version": 1, "content": content}


def adf_text(t: str) -> dict:
    return {"type": "text", "text": t}


def adf_para(t: str | None = None) -> dict:
    return {"type": "paragraph", "content": [adf_text(t)] if t else []}


def adf_bullets(items: list) -> dict:
    li = []
    for i in items:
        p = i if isinstance(i, dict) else adf_para(str(i))
        li.append({"type": "listItem", "content": [p]})
    return {"type": "bulletList", "content": li}


def adf_to_text(node) -> str:
    parts: list[str] = []
    _walk(node, parts, "")
    return re.sub(r"\n{3,}", "\n\n", "".join(parts)).strip()


def _walk(node, buf: list[str], indent: str):
    if isinstance(node, str):
        buf.append(node)
        return
    if not isinstance(node, dict):
        return
    ntype = node.get("type", "")
    kids = node.get("content") or []

    if ntype == "text":
        buf.append(node.get("text", ""))
    elif ntype == "hardBreak":
        buf.append(f"\n{indent}")
    elif ntype == "mention":
        buf.append((node.get("attrs") or {}).get("text", "@user"))
    elif ntype == "emoji":
        buf.append((node.get("attrs") or {}).get("text", ""))
    elif ntype == "inlineCard":
        buf.append((node.get("attrs") or {}).get("url", ""))
    elif ntype == "heading":
        level = (node.get("attrs") or {}).get("level", 2)
        buf.append(f"\n{'#' * max(1, level)} ")
        for k in kids:
            _walk(k, buf, indent)
        buf.append("\n")
    elif ntype == "paragraph":
        for k in kids:
            _walk(k, buf, indent)
        buf.append("\n")
    elif ntype in ("bulletList", "orderedList"):
        n = 1
        for k in kids:
            buf.append(f"{indent}{'- ' if ntype == 'bulletList' else f'{n}. '}")
            n += 1
            item: list[str] = []
            for kk in (k.get("content") or []):
                _walk(kk, item, indent + "  ")
            buf.append("".join(item).strip().replace("\n", f"\n{indent}  "))
            buf.append("\n")
    elif ntype == "codeBlock":
        buf.append("```\n")
        for k in kids:
            _walk(k, buf, indent)
        buf.append("\n```\n")
    elif ntype == "tableRow":
        cells = []
        for k in kids:
            cell: list[str] = []
            _walk(k, cell, "")
            cells.append("".join(cell).strip().replace("\n", " "))
        buf.append(f"| {' | '.join(cells)} |\n")
    else:
        for k in kids:
            _walk(k, buf, indent)
        if ntype in ("blockquote", "panel", "table"):
            buf.append("\n")


# ── approval gate ─────────────────────────────────────────────────────────


class Gate:
    def __init__(self):
        self.approved = False
        self.issue: dict = {}
        self.checks: list[dict] = []
        self.approval: dict = {}

    def check(self, name: str, passed: bool, detail: str):
        self.checks.append({"name": name, "pass": passed, "detail": detail})

    def failed_check_names(self) -> list[str]:
        return [c["name"] for c in self.checks if not c["pass"]]

    def to_json(self) -> dict:
        return {
            "verdict": "APPROVED" if self.approved else "REJECTED",
            "approval": self.approval,
            "checks": self.checks,
        }

    @classmethod
    def evaluate(cls, jira: Jira, cfg: Config, key: str) -> Gate:
        g = cls()
        field_list = [
            "summary", "description", "status", "issuetype",
            "project", "priority", "labels", "reporter", "creator", "parent",
        ]
        if cfg.approver_field:
            field_list.append(cfg.approver_field)
        g.issue = jira.get(
            f"/rest/api/3/issue/{quote(key)}?fields={quote(','.join(field_list))}"
        )
        f = g.issue.get("fields") or {}

        itype = _deep(f, "issuetype", "name") or ""
        is_task = any(t.lower() == itype.lower() for t in cfg.task_issue_types)
        g.check("isTask", is_task, f"issue type is '{itype}'; accepted {cfg.task_issue_types}")

        status = _deep(f, "status", "name") or ""
        g.check("currentStatusApproved", cfg.is_approved_status(status),
                f"current status '{status}'; approved statuses {cfg.approved_statuses}")

        history = jira.changelog(key)
        history.sort(key=lambda h: h.get("created") or "")
        approval_event = None
        approval_idx = -1
        for i, entry in enumerate(history):
            for item in entry.get("items", []):
                if (item.get("field") == "status"
                        and cfg.is_approved_status(item.get("toString"))):
                    approval_event = entry
                    approval_idx = i

        approver_id = _deep(approval_event, "author", "accountId") if approval_event else None
        if approval_event is None:
            d3 = "no status change into an approved status in the changelog"
        elif approver_id is None:
            d3 = "approval transition has no author (anonymous or deleted user)"
        else:
            d3 = f"approved at {approval_event.get('created')}"
        g.check("approvalTransitionFound",
                approval_event is not None and approver_id is not None, d3)
        if approval_event:
            g.approval["approvedAt"] = approval_event.get("created")
            g.approval["approverAccountId"] = approver_id

        approver_ok = approver_id is not None and cfg.is_authorized(jira, approver_id)
        if not cfg.has_any_approver():
            d4 = "authorizedApprovers is empty in config - nobody is authorized (fail closed)"
        elif approver_id is None:
            d4 = "no approver to check"
        elif approver_ok:
            d4 = "approver is in authorizedApprovers"
        else:
            d4 = f"approver is not in authorizedApprovers.accountIds or any of groups {cfg.approver_groups}"
        g.check("approverAuthorized", approver_ok, d4)

        if cfg.forbid_self_approval:
            reporter = _deep(f, "reporter", "accountId")
            creator = _deep(f, "creator", "accountId")
            is_self = approver_id is not None and approver_id in (reporter, creator)
            if approver_id is None:
                d5 = "no approver to check"
            elif is_self:
                d5 = "approver is the task's reporter/creator"
            else:
                d5 = "approver differs from reporter/creator"
            g.check("notSelfApproved", approver_id is not None and not is_self, d5)

        if cfg.approver_field:
            recorded = _account_ids(f.get(cfg.approver_field))
            any_auth = any(cfg.is_authorized(jira, aid) for aid in recorded)
            matches = (
                not cfg.approver_field_must_match_transition
                or (approver_id is not None and approver_id in recorded)
            )
            if not recorded:
                d6 = f"approval field {cfg.approver_field} is empty"
            elif not any_auth:
                d6 = f"no authorized approver in {cfg.approver_field}"
            elif not matches:
                d6 = f"person in {cfg.approver_field} is not the one who made the approval transition"
            else:
                d6 = f"{cfg.approver_field} names an authorized approver"
            g.check("approverFieldValid", bool(recorded) and any_auth and matches, d6)
            g.approval["approverFieldAccountIds"] = recorded

        if cfg.reject_if_edited_after_approval and approval_idx >= 0:
            edits = []
            for entry in history[approval_idx + 1:]:
                for item in entry.get("items", []):
                    fname = item.get("field")
                    if fname in cfg.protected_fields:
                        edits.append(f"{fname} @ {entry.get('created')}")
            g.check(
                "unchangedSinceApproval", not edits,
                f"no edits to {cfg.protected_fields} after approval" if not edits
                else f"edited after approval (re-approval needed): {edits}",
            )

        story_key = _deep(f, "parent", "key")
        g.approval["storyKey"] = story_key
        if cfg.require_approved_story:
            if story_key is None:
                g.check("parentStoryApproved", False, "task has no parent story")
            else:
                story = jira.get(f"/rest/api/3/issue/{quote(story_key)}?fields=status")
                ss = _deep(story, "fields", "status", "name") or ""
                g.check(
                    "parentStoryApproved",
                    _matches_any(cfg.story_approved_statuses, ss),
                    f"story {story_key} status '{ss}'; accepted {cfg.story_approved_statuses}",
                )

        g.approved = all(c["pass"] for c in g.checks)
        return g


# ── acceptance criteria extraction ────────────────────────────────────────


def _extract_criteria(text: str | None) -> list[str]:
    if not text:
        return []
    out: list[str] = []
    in_section = False
    for raw in text.split("\n"):
        line = raw.strip()
        if line.startswith("#"):
            in_section = "acceptance criteria" in line.lower()
            continue
        if not in_section or not line:
            continue
        item = re.sub(r"^(-|\*|\d+[.)])\s+", "", line)
        if item != line or out:
            if item == line and out:
                out[-1] += " " + item
            else:
                out.append(item)
    return out


# ── commands ──────────────────────────────────────────────────────────────


def cmd_scan(jira: Jira, cfg: Config, *, story=None, project=None, jql_override=None) -> int:
    if jql_override:
        jql = jql_override
    else:
        parts = [
            f"issuetype in ({_quoted(cfg.task_issue_types)})",
            f"AND status in ({_quoted(cfg.approved_statuses)})",
        ]
        if story:
            parts.append(f"AND parent = {story}")
        p = project or cfg.project_key
        if p and not story:
            parts.append(f'AND project = "{p}"')
        parts.append("ORDER BY rank ASC")
        jql = " ".join(parts)

    tasks = jira.search(jql, ["summary", "status", "attachment"], cfg.scan_limit)
    rows = []
    for t in tasks:
        key = t.get("key")
        g = Gate.evaluate(jira, cfg, key)
        existing = []
        if g.approved:
            for att in (t.get("fields") or {}).get("attachment", []):
                fn = att.get("filename", "")
                if fn.startswith("architecture-"):
                    existing.append(fn)
        rows.append({
            "key": key,
            "summary": _deep(t, "fields", "summary"),
            "status": _deep(t, "fields", "status", "name"),
            "story": g.approval.get("storyKey"),
            "gate": "APPROVED" if g.approved else "REJECTED",
            "failedChecks": g.failed_check_names(),
            "existingArchDocs": existing,
        })
    _out({"jql": jql, "tasks": rows})
    return EXIT_OK


def cmd_read(jira: Jira, cfg: Config, key: str) -> int:
    g = Gate.evaluate(jira, cfg, key)
    out: dict = {"taskKey": key, "gate": g.to_json()}
    if g.approved:
        f = g.issue.get("fields") or {}
        text = adf_to_text(f.get("description"))
        out["task"] = {
            "summary": f.get("summary"),
            "description": text,
            "project": _deep(f, "project", "key"),
            "story": g.approval.get("storyKey"),
            "priority": _deep(f, "priority", "name"),
            "labels": f.get("labels", []),
        }
        criteria = _extract_criteria(text)
        out["acceptanceCriteria"] = {f"AC{i + 1}": c for i, c in enumerate(criteria)}

        story_key = g.approval.get("storyKey")
        if story_key:
            try:
                story = jira.get(
                    f"/rest/api/3/issue/{quote(story_key)}?fields=summary,description"
                )
                sf = story.get("fields") or {}
                out["storyContext"] = {
                    "summary": sf.get("summary"),
                    "description": adf_to_text(sf.get("description")),
                }
            except JiraError:
                out["storyContext"] = None

            try:
                siblings = jira.search(
                    f'parent = {story_key} AND key != {key}',
                    ["summary", "status"], 50,
                )
                out["siblingTasks"] = [
                    {
                        "key": s.get("key"),
                        "summary": _deep(s, "fields", "summary"),
                        "status": _deep(s, "fields", "status", "name"),
                    }
                    for s in siblings
                ]
            except JiraError:
                out["siblingTasks"] = []

        with_atts = jira.get(f"/rest/api/3/issue/{quote(key)}?fields=attachment")
        existing = []
        for att in (with_atts.get("fields") or {}).get("attachment", []):
            fn = att.get("filename", "")
            if fn.startswith("architecture-"):
                existing.append({"id": att.get("id"), "filename": fn})
        out["existingArchDocs"] = existing

    _out(out)
    return EXIT_OK if g.approved else EXIT_REJECTED


def cmd_attach(cfg: Config, key: str, file_path: Path, dry_run: bool) -> int:
    if not file_path.exists():
        _out({"taskKey": key, "error": f"file not found: {file_path}", "attached": None})
        return EXIT_ERROR

    jira = Jira.from_env()
    g = Gate.evaluate(jira, cfg, key)
    out: dict = {"taskKey": key, "dryRun": dry_run, "gate": g.to_json()}
    if not g.approved:
        out["attached"] = None
        _out(out)
        return EXIT_REJECTED

    file_bytes = file_path.read_bytes()
    filename = cfg.attachment_filename.replace("{taskKey}", key)
    suffix = file_path.suffix.lower()
    content_type = {
        ".md": "text/markdown",
        ".pdf": "application/pdf",
        ".html": "text/html",
    }.get(suffix, "application/octet-stream")

    out["filename"] = filename
    out["sizeBytes"] = len(file_bytes)

    if dry_run:
        out["attached"] = None
        out["note"] = "dry-run: file read, gate checked, no attachment uploaded"
        _out(out)
        return EXIT_OK

    replaced: list[dict] = []
    with_atts = jira.get(f"/rest/api/3/issue/{quote(key)}?fields=attachment")
    for att in (with_atts.get("fields") or {}).get("attachment", []):
        if (att.get("filename") or "").lower() != filename.lower():
            continue
        att_id = att.get("id")
        if cfg.on_existing_attachment == "fail":
            out["error"] = (
                f"attachment '{filename}' already exists on {key}"
                f" (id={att_id}) and onExistingAttachment=fail"
            )
            out["attached"] = None
            _out(out)
            return EXIT_ERROR
        try:
            jira.delete(f"/rest/api/3/attachment/{quote(str(att_id))}")
            replaced.append({"id": att_id, "filename": filename})
        except JiraError as e:
            out["error"] = f"could not delete existing attachment {att_id}: {e}"
            out["attached"] = None
            _out(out)
            return EXIT_ERROR
    out["replaced"] = replaced

    try:
        att_resp = jira.post_multipart(
            f"/rest/api/3/issue/{quote(key)}/attachments",
            filename, content_type, file_bytes,
        )
    except JiraError as e:
        out["error"] = f"attachment upload failed: {e}"
        out["attached"] = None
        _out(out)
        return EXIT_ERROR

    att_list = att_resp if isinstance(att_resp, list) else []
    first = att_list[0] if att_list else {}
    out["attached"] = {
        "id": first.get("id"),
        "filename": first.get("filename"),
        "size": first.get("size"),
        "url": first.get("content"),
    }

    if cfg.comment_on_task:
        body_lines = [f"Attachment: {filename} (high-level architecture document)"]
        if replaced:
            r = len(replaced)
            body_lines.append(f"Replaced {r} previous version{'s' if r != 1 else ''}.")
        content = [
            adf_para("Architecture Agent attached a high-level architecture document for this approved task:"),
            adf_bullets(body_lines),
        ]
        try:
            jira.post(
                f"/rest/api/3/issue/{quote(key)}/comment",
                {"body": adf_doc(content)},
            )
        except JiraError as e:
            out["commentError"] = str(e)

    _out(out)
    return EXIT_OK


# ── helpers ───────────────────────────────────────────────────────────────


def _deep(obj, *keys):
    for k in keys:
        if not isinstance(obj, dict):
            return None
        obj = obj.get(k)
    return obj


def _matches_any(lst: list[str], s: str | None) -> bool:
    if s is None:
        return False
    return any(a.lower() == s.strip().lower() for a in lst)


def _strings(v) -> list[str]:
    if not isinstance(v, list):
        return []
    return [str(x).strip() for x in v if x is not None and str(x).strip()]


def _blank_to_none(s) -> str | None:
    if s is None or not str(s).strip():
        return None
    return str(s).strip()


def _quoted(values: list[str]) -> str:
    return ", ".join(f'"{v}"' for v in values)


def _account_ids(v) -> list[str]:
    if isinstance(v, dict):
        aid = v.get("accountId")
        return [aid] if aid else []
    if isinstance(v, list):
        out: list[str] = []
        for item in v:
            out.extend(_account_ids(item))
        return out
    return []


def _load_env_file(path: Path) -> dict[str, str]:
    env: dict[str, str] = {}
    if not path.exists():
        return env
    for line in path.read_text(encoding="utf-8").splitlines():
        line = line.strip()
        if not line or line.startswith("#"):
            continue
        eq = line.find("=")
        if eq <= 0:
            continue
        key = line[:eq].strip()
        value = line[eq + 1:].strip()
        if len(value) >= 2 and (value[0] == value[-1]) and value[0] in ('"', "'"):
            value = value[1:-1]
        env[key] = value
    return env


def _out(obj):
    print(json.dumps(obj, indent=2, ensure_ascii=False))


# ── main ──────────────────────────────────────────────────────────────────


def main():
    parser = argparse.ArgumentParser(
        description="Architecture agent helper",
        formatter_class=argparse.RawDescriptionHelpFormatter,
        epilog="exit: 0 ok | 2 gate rejected | 1 error | 64 usage",
    )
    sub = parser.add_subparsers(dest="command")

    p_scan = sub.add_parser("scan", help="approved tasks with gate verdict")
    p_scan.add_argument("--story")
    p_scan.add_argument("--project")
    p_scan.add_argument("--jql")
    p_scan.add_argument("--config")

    p_read = sub.add_parser("read", help="gate + task content + story context")
    p_read.add_argument("task_key")
    p_read.add_argument("--config")

    p_att = sub.add_parser("attach", help="gate + upload file to task")
    p_att.add_argument("task_key")
    p_att.add_argument("file_path")
    p_att.add_argument("--dry-run", action="store_true")
    p_att.add_argument("--config")

    args = parser.parse_args()
    if not args.command:
        parser.print_help()
        sys.exit(EXIT_USAGE)

    config_path = Path(
        args.config or ".claude/skills/architecture-agent/architecture-agent.config.json"
    )
    cfg = Config.load(config_path)

    if args.command == "scan":
        sys.exit(cmd_scan(
            Jira.from_env(), cfg,
            story=args.story, project=args.project, jql_override=args.jql,
        ))
    elif args.command == "read":
        sys.exit(cmd_read(Jira.from_env(), cfg, args.task_key))
    elif args.command == "attach":
        sys.exit(cmd_attach(cfg, args.task_key, Path(args.file_path), args.dry_run))


if __name__ == "__main__":
    try:
        main()
    except SystemExit:
        raise
    except Exception as e:
        print(f"error: {e}", file=sys.stderr)
        sys.exit(EXIT_ERROR)
