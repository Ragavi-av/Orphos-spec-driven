#!/usr/bin/env python3
"""
Test Case agent helper for the test-case-agent Claude skill.

Talks to Jira Cloud REST v3 with API-token Basic Auth from
JIRA_URL, JIRA_EMAIL, JIRA_TOKEN. Credentials are never printed.

Same fail-closed approval gate as the story-agent helper, applied to a Jira
task: test cases are released and created only for an approved task whose
approval is genuine. ``create`` validates every test case first (all or nothing),
enforces scenario coverage and acceptance-criteria traceability, drops duplicates,
and re-runs the gate immediately before writing.

Output never carries e-mail addresses or display names -- people are identified
by Jira accountId only.
"""
from __future__ import annotations

import argparse
import base64
import hashlib
import json
import os
import re
import sys
from io import BytesIO
from pathlib import Path
from urllib.parse import quote

import requests
from openpyxl import Workbook
from openpyxl.styles import Alignment, Font

EXIT_OK, EXIT_ERROR, EXIT_REJECTED, EXIT_INVALID, EXIT_USAGE = 0, 1, 2, 3, 64

TYPES = ["positive", "negative", "edge", "validation", "tenant-isolation"]
COVERAGE_TYPES = ["negative", "edge", "validation", "tenant-isolation"]
AUTOMATION = {"unit", "integration", "e2e", "manual"}

# ── config ───────────────────────────────────────────────────────────────


class Config:
    def __init__(self, data: dict):
        self.project_key = _blank_to_none(data.get("projectKey"))
        self.task_issue_types = _strings(data.get("taskIssueTypes")) or ["Task", "Sub-task"]
        self.test_case_issue_type = data.get("testCaseIssueType") or "Test"
        self.issue_link_type = data.get("issueLinkType") or "Relates"
        self.attachment_filename = (
            _blank_to_none(data.get("attachmentFilename")) or "testcases-{taskKey}.xlsx"
        )
        self.on_existing_attachment = (
            _blank_to_none(data.get("onExistingAttachment")) or "replace"
        ).lower()
        if self.on_existing_attachment not in ("replace", "fail"):
            raise SystemExit(
                f"onExistingAttachment must be 'replace' or 'fail', got '{self.on_existing_attachment}'"
            )
        self.approver_field = _blank_to_none(data.get("approverField"))
        self.approved_statuses = _strings(data.get("approvedStatuses")) or ["Approved"]
        self.story_approved_statuses = (
            _strings(data.get("storyApprovedStatuses")) or ["Approved", "In Progress"]
        )
        aa = data.get("authorizedApprovers") or {}
        self.approver_account_ids: list[str] = _strings(aa.get("accountIds"))
        self.approver_groups: list[str] = _strings(aa.get("groups"))
        self.protected_fields = _strings(data.get("protectedFields")) or ["summary", "description"]
        self.default_labels: list[str] = _strings(data.get("defaultLabels"))
        self.forbid_self_approval: bool = data.get("forbidSelfApproval", True)
        self.reject_if_edited_after_approval: bool = data.get("rejectIfEditedAfterApproval", True)
        self.approver_field_must_match_transition: bool = data.get(
            "approverFieldMustMatchTransition", True
        )
        self.require_approved_story: bool = data.get("requireApprovedStory", True)
        self.comment_on_task: bool = data.get("commentOnTask", True)
        self.similarity_threshold: float = float(data.get("similarityThreshold", 0.85))
        self.max_test_cases_per_run: int = int(data.get("maxTestCasesPerRun", 40))
        self.scan_limit: int = int(data.get("scanLimit", 50))
        self._group_cache: dict[str, set[str]] = {}

    @classmethod
    def load(cls, path: Path) -> Config:
        if not path.exists():
            raise SystemExit(
                f"config not found at {path}"
                " - copy test-case-agent.config.example.json"
                " to test-case-agent.config.json and fill it in"
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
                f"&fields={quote(','.join(fields))}"
                f"&maxResults={min(100, limit - len(results))}"
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


def adf_code(t: str) -> dict:
    return {"type": "text", "text": t, "marks": [{"type": "code"}]}


def adf_para(t: str | None = None) -> dict:
    return {"type": "paragraph", "content": [adf_text(t)] if t else []}


def adf_para_of(inline: list) -> dict:
    return {"type": "paragraph", "content": inline}


def adf_heading(t: str) -> dict:
    return {"type": "heading", "attrs": {"level": 3}, "content": [adf_text(t)]}


def adf_bullets(items: list) -> dict:
    li = []
    for i in items:
        p = i if isinstance(i, dict) else adf_para(str(i))
        li.append({"type": "listItem", "content": [p]})
    return {"type": "bulletList", "content": li}


def adf_numbered(items: list) -> dict:
    return {"type": "orderedList", "content": [
        {"type": "listItem", "content": [i]} for i in items
    ]}


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

        # 1. It is a task (not an epic, story or bug).
        itype = _deep(f, "issuetype", "name") or ""
        is_task = any(t.lower() == itype.lower() for t in cfg.task_issue_types)
        g.check("isTask", is_task,
                f"issue type is '{itype}'; accepted {cfg.task_issue_types}")

        # 2. Currently in an approved status.
        status = _deep(f, "status", "name") or ""
        g.check("currentStatusApproved", cfg.is_approved_status(status),
                f"current status '{status}'; approved statuses {cfg.approved_statuses}")

        # 3. Changelog: who moved it into that status, and when.
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
            detail3 = "no status change into an approved status in the changelog"
        elif approver_id is None:
            detail3 = "approval transition has no author (anonymous or deleted user)"
        else:
            detail3 = f"approved at {approval_event.get('created')}"
        g.check("approvalTransitionFound",
                approval_event is not None and approver_id is not None, detail3)
        if approval_event:
            g.approval["approvedAt"] = approval_event.get("created")
            g.approval["approverAccountId"] = approver_id

        # 4. Whoever made that transition is an authorized approver.
        approver_ok = approver_id is not None and cfg.is_authorized(jira, approver_id)
        if not cfg.has_any_approver():
            detail4 = "authorizedApprovers is empty in config - nobody is authorized (fail closed)"
        elif approver_id is None:
            detail4 = "no approver to check"
        elif approver_ok:
            detail4 = "approver is in authorizedApprovers"
        else:
            detail4 = (
                "approver is not in authorizedApprovers.accountIds"
                f" or any of groups {cfg.approver_groups}"
            )
        g.check("approverAuthorized", approver_ok, detail4)

        # 5. Segregation of duties: the author cannot approve their own task.
        if cfg.forbid_self_approval:
            reporter = _deep(f, "reporter", "accountId")
            creator = _deep(f, "creator", "accountId")
            is_self = approver_id is not None and approver_id in (reporter, creator)
            if approver_id is None:
                detail5 = "no approver to check"
            elif is_self:
                detail5 = "approver is the task's reporter/creator"
            else:
                detail5 = "approver differs from reporter/creator"
            g.check("notSelfApproved", approver_id is not None and not is_self, detail5)

        # 6. Optional approval-record field.
        if cfg.approver_field:
            recorded = _account_ids(f.get(cfg.approver_field))
            any_auth = any(cfg.is_authorized(jira, aid) for aid in recorded)
            matches = (
                not cfg.approver_field_must_match_transition
                or (approver_id is not None and approver_id in recorded)
            )
            if not recorded:
                detail6 = f"approval field {cfg.approver_field} is empty"
            elif not any_auth:
                detail6 = f"no authorized approver in {cfg.approver_field}"
            elif not matches:
                detail6 = (
                    f"person in {cfg.approver_field} is not the one"
                    " who made the approval transition"
                )
            else:
                detail6 = f"{cfg.approver_field} names an authorized approver"
            g.check("approverFieldValid", bool(recorded) and any_auth and matches, detail6)
            g.approval["approverFieldAccountIds"] = recorded

        # 7. No edits to protected fields after approval.
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

        # 8. Parent story must still be approved.
        story_key = _deep(f, "parent", "key")
        g.approval["storyKey"] = story_key
        if cfg.require_approved_story:
            if story_key is None:
                g.check("parentStoryApproved", False, "task has no parent story")
            else:
                story = jira.get(
                    f"/rest/api/3/issue/{quote(story_key)}?fields=status"
                )
                ss = _deep(story, "fields", "status", "name") or ""
                g.check(
                    "parentStoryApproved",
                    _matches_any(cfg.story_approved_statuses, ss),
                    f"story {story_key} status '{ss}'; accepted {cfg.story_approved_statuses}",
                )

        g.approved = all(c["pass"] for c in g.checks)
        return g


# ── duplicate prevention ──────────────────────────────────────────────────


def fingerprint(tc: dict) -> str:
    parts = [_norm(tc.get("type"))]
    for s in tc.get("steps", []):
        parts.append(f"|{_norm(s.get('action'))}>{_norm(s.get('expected'))}")
    return hashlib.sha256("".join(parts).encode()).hexdigest()[:10]


def _jaccard(a: str, b: str) -> float:
    x, y = set(a.split()), set(b.split())
    if not x or not y:
        return 0.0
    return len(x & y) / len(x | y)


def _match_dup(known: list[dict], title: str, fp: str, threshold: float) -> str | None:
    nt = _norm(title)
    for k in known:
        if _norm(k["title"]) == nt:
            return f"{k['key']} (same title)"
        if f"tc-fp-{fp}" in k.get("labels", []):
            return f"{k['key']} (same steps and expected results)"
        if _jaccard(nt, _norm(k["title"])) >= threshold:
            return f"{k['key']} (near-identical title)"
    return None


# ── validation ────────────────────────────────────────────────────────────


def validate_doc(
    cfg: Config, key: str, doc: dict, known_criteria: list[str] | None,
) -> list[str]:
    problems: list[str] = []
    file_task = doc.get("taskKey")
    if not file_task or file_task.upper() != key.upper():
        problems.append(f"testcases file is for {file_task}, not {key}")

    cases = doc.get("testCases", [])
    if not cases:
        problems.append("testcases file has no testCases")
        return problems
    if len(cases) > cfg.max_test_cases_per_run:
        problems.append(
            f"{len(cases)} test cases exceeds maxTestCasesPerRun={cfg.max_test_cases_per_run}"
        )

    present: set[str] = set()
    covered: set[str] = set()
    seen: list[dict] = []

    for i, tc in enumerate(cases):
        where = f"testCases[{i}] '{tc.get('title', '?')}'"

        title = tc.get("title")
        if not title or not title.strip():
            problems.append(f"{where}: missing title")
        tc_type = tc.get("type")
        if not tc_type or tc_type not in TYPES:
            problems.append(f"{where}: type must be one of {TYPES}")
        else:
            present.add(tc_type)
        auto = tc.get("automation")
        if auto is not None and auto not in AUTOMATION:
            problems.append(f"{where}: automation must be one of {sorted(AUTOMATION)}")

        steps = tc.get("steps", [])
        if not steps:
            problems.append(f"{where}: needs at least one step")
        for s in steps:
            if not s.get("action") or not s.get("expected"):
                problems.append(f"{where}: every step needs 'action' and 'expected'")
                break

        covers = tc.get("covers", [])
        objective_only = tc.get("coversObjective") is True
        if not covers and not objective_only:
            problems.append(
                f'{where}: needs \'covers\' (AC ids like AC1) or "coversObjective": true'
            )
        for c in covers:
            cid = str(c).strip().upper()
            if not re.match(r"AC\d+$", cid):
                problems.append(f"{where}: covers entry '{c}' is not an AC id (AC1, AC2, ...)")
            elif known_criteria is not None and cid not in known_criteria:
                problems.append(f"{where}: covers {cid} but the task has only {known_criteria}")
            else:
                covered.add(cid)

        if title and title.strip():
            fp = fingerprint(tc)
            dup = _match_dup(seen, title, fp, cfg.similarity_threshold)
            if dup:
                problems.append(
                    f"{where}: duplicate of another test case in this file ({dup})"
                )
            seen.append({"key": title, "title": title, "labels": [f"tc-fp-{fp}"]})

    if "positive" not in present:
        problems.append("coverage: at least one positive test case is required")

    na = (doc.get("coverage") or {}).get("notApplicable") or {}
    for t in COVERAGE_TYPES:
        if t in present:
            if t in na:
                problems.append(f"coverage: '{t}' is both present and declared notApplicable")
        elif not (na.get(t) or "").strip():
            problems.append(
                f"coverage: no '{t}' test case and no coverage.notApplicable.{t}"
                " reason (justify it or add the test)"
            )
    for t in na:
        if t not in COVERAGE_TYPES:
            problems.append(f"coverage: notApplicable key '{t}' is not one of {COVERAGE_TYPES}")

    if known_criteria is not None:
        for cid in known_criteria:
            if cid not in covered:
                problems.append(f"traceability: {cid} is not covered by any test case")

    return problems


def _matrix(doc: dict) -> dict:
    by_type: dict[str, int] = {}
    by_ac: dict[str, list[str]] = {}
    for tc in doc.get("testCases", []):
        t = tc.get("type", "?")
        by_type[t] = by_type.get(t, 0) + 1
        for c in tc.get("covers", []):
            by_ac.setdefault(str(c).upper(), []).append(tc.get("title", ""))
    return {
        "byType": by_type,
        "byAcceptanceCriterion": by_ac,
        "notApplicable": (doc.get("coverage") or {}).get("notApplicable") or {},
    }


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


# ── description builder (plain text for the xlsx Description cell) ────────


def _describe(tc: dict, task_key: str, story_key: str | None, criteria: list[str]) -> str:
    lines: list[str] = [f"Verifies task: {task_key}"]
    if story_key:
        lines.append(f"Story: {story_key}")
    auto = tc.get("automation") or ""
    lines.append(f"Scenario type: {tc.get('type', '')}" + (f" | Automation: {auto}" if auto else ""))

    covers = tc.get("covers", [])
    if covers:
        lines.append("\nCovers:")
        for c in covers:
            cid = str(c).strip().upper()
            idx = int(cid[2:]) - 1 if re.match(r"AC\d+$", cid) else -1
            ac = criteria[idx] if 0 <= idx < len(criteria) else ""
            lines.append(f"  - {cid}" + (f" - {ac}" if ac else ""))
    elif tc.get("coversObjective") is True:
        lines.append("\nCovers: task objective")

    obj = tc.get("objective")
    if obj and obj.strip():
        lines.append(f"\nObjective:\n{obj}")

    pre = tc.get("preconditions", [])
    if pre:
        lines.append("\nPreconditions:")
        for p in pre:
            lines.append(f"  - {p}")

    steps = tc.get("steps", [])
    if steps:
        lines.append("\nSteps:")
        for n, s in enumerate(steps, 1):
            lines.append(f"{n}. {s.get('action', '')}")
            lines.append(f"   Expected: {s.get('expected', '')}")

    expected = tc.get("expected")
    if expected and expected.strip():
        lines.append(f"\nExpected result:\n{expected}")

    data = tc.get("testData", [])
    if data:
        lines.append("\nTest data:")
        for d in data:
            lines.append(f"  - {d}")

    lines.append(f"\nGenerated by Test Case Agent from approved task {task_key}.")
    return "\n".join(lines)


# ── labels ────────────────────────────────────────────────────────────────


def _add_label(labels: list[str], raw: str | None):
    if raw is None:
        return
    v = re.sub(r"[^a-z0-9._-]+", "-", raw.strip().lower())
    if v and not v.endswith("-null") and v not in labels:
        labels.append(v)


# ── xlsx writer ───────────────────────────────────────────────────────────


def _build_xlsx(headers: list[str], rows: list[list[str]]) -> bytes:
    wb = Workbook()
    ws = wb.active
    ws.title = "Test Cases"

    bold = Font(name="Calibri", size=11, bold=True)
    wrap = Alignment(wrap_text=True, vertical="top")

    for i, h in enumerate(headers, 1):
        col_letter = ws.cell(1, i).column_letter
        ws.column_dimensions[col_letter].width = 70 if i == 3 else 22
        cell = ws.cell(1, i, h)
        cell.font = bold

    for r, row in enumerate(rows, 2):
        for c, val in enumerate(row, 1):
            cell = ws.cell(r, c, val)
            cell.alignment = wrap

    buf = BytesIO()
    wb.save(buf)
    return buf.getvalue()


# ── existing tests ────────────────────────────────────────────────────────


def _existing_tests(jira: Jira, cfg: Config, task_key: str) -> list[dict]:
    jql = f'issue in linkedIssues("{task_key}") AND issuetype = "{cfg.test_case_issue_type}"'
    return jira.search(jql, ["summary", "status", "labels"], 500)


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

    tasks = jira.search(jql, ["summary", "status"], cfg.scan_limit)
    rows = []
    for t in tasks:
        key = t.get("key")
        g = Gate.evaluate(jira, cfg, key)
        rows.append({
            "key": key,
            "summary": _deep(t, "fields", "summary"),
            "status": _deep(t, "fields", "status", "name"),
            "story": g.approval.get("storyKey"),
            "gate": "APPROVED" if g.approved else "REJECTED",
            "failedChecks": g.failed_check_names(),
            "existingTestCases": len(_existing_tests(jira, cfg, key)) if g.approved else None,
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
        out["existingTestCases"] = [
            {
                "key": t.get("key"),
                "status": _deep(t, "fields", "status", "name"),
                "summary": _deep(t, "fields", "summary"),
            }
            for t in _existing_tests(jira, cfg, key)
        ]
    _out(out)
    return EXIT_OK if g.approved else EXIT_REJECTED


def cmd_validate(cfg: Config, key: str, file: Path) -> int:
    doc = json.loads(file.read_text(encoding="utf-8-sig"))
    problems = validate_doc(cfg, key, doc, None)
    _out({"taskKey": key, "valid": not problems, "problems": problems, "matrix": _matrix(doc)})
    return EXIT_OK if not problems else EXIT_INVALID


def cmd_create(cfg: Config, key: str, file: Path, dry_run: bool, output_dir: Path | None = None) -> int:
    doc = json.loads(file.read_text(encoding="utf-8-sig"))
    jira = Jira.from_env()

    g = Gate.evaluate(jira, cfg, key)
    out: dict = {"taskKey": key, "dryRun": dry_run, "gate": g.to_json()}
    if not g.approved:
        out["attached"] = None
        _out(out)
        return EXIT_REJECTED

    f = g.issue.get("fields") or {}
    text = adf_to_text(f.get("description"))
    criteria = _extract_criteria(text)
    criteria_ids = [f"AC{i + 1}" for i in range(len(criteria))]
    problems = validate_doc(cfg, key, doc, criteria_ids)
    if problems:
        out["invalid"] = problems
        out["attached"] = None
        _out(out)
        return EXIT_INVALID

    story_key = g.approval.get("storyKey")
    cases = doc.get("testCases", [])
    issue_type = cfg.test_case_issue_type or "Test"

    headers = [
        "Summary", "Issue Type", "Description", "Priority",
        "Labels", "Scenario Type", "Automation", "Covers",
        "Task Key", "Story Key",
    ]
    rows: list[list[str]] = []
    for tc in cases:
        title = (tc.get("title") or "").strip()[:255]
        tc_type = tc.get("type") or ""
        automation = tc.get("automation") or ""
        priority = tc.get("priority") or ""

        covers_ids = [str(c) for c in tc.get("covers", [])]
        covers_str = (
            "Objective" if not covers_ids and tc.get("coversObjective") is True
            else ", ".join(covers_ids)
        )

        labels: list[str] = list(cfg.default_labels)
        _add_label(labels, f"task-{key}")
        if story_key:
            _add_label(labels, f"story-{story_key}")
        _add_label(labels, f"type-{tc_type}")
        if automation:
            _add_label(labels, f"automation-{automation}")
        for lbl in tc.get("labels", []):
            _add_label(labels, str(lbl))

        rows.append([
            title, issue_type,
            _describe(tc, key, story_key, criteria),
            priority, " ".join(labels),
            tc_type, automation, covers_str,
            key, story_key or "",
        ])

    xlsx_bytes = _build_xlsx(headers, rows)
    filename = cfg.attachment_filename.replace("{taskKey}", key)

    # Always save the workbook locally first
    save_dir = output_dir or Path(".")
    save_dir.mkdir(parents=True, exist_ok=True)
    local_path = save_dir / filename
    local_path.write_bytes(xlsx_bytes)

    out["filename"] = filename
    out["localPath"] = str(local_path.resolve())
    out["testCount"] = len(cases)
    out["sizeBytes"] = len(xlsx_bytes)
    out["matrix"] = _matrix(doc)

    if dry_run:
        out["attached"] = None
        out["note"] = f"dry-run: xlsx saved locally at {local_path.resolve()}, no attachment uploaded"
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
            filename,
            "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet",
            xlsx_bytes,
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
        n = len(cases)
        body_lines: list[str] = [
            f"Attachment: {filename} ({n} test case{'s' if n != 1 else ''})"
        ]
        if replaced:
            r = len(replaced)
            body_lines.append(
                f"Replaced {r} previous version{'s' if r != 1 else ''} of this file."
            )
        content = [
            adf_para(
                "Test Case Agent attached an updated test case workbook"
                " for this approved task:"
            ),
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


def _norm(s: str | None) -> str:
    if not s:
        return ""
    return re.sub(r"[^a-z0-9]+", " ", s.strip().lower()).strip()


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
        if (
            len(value) >= 2
            and (value[0] == value[-1])
            and value[0] in ('"', "'")
        ):
            value = value[1:-1]
        env[key] = value
    return env


def _out(obj):
    print(json.dumps(obj, indent=2, ensure_ascii=False))


# ── main ──────────────────────────────────────────────────────────────────


def main():
    parser = argparse.ArgumentParser(
        description="Test Case agent helper",
        formatter_class=argparse.RawDescriptionHelpFormatter,
        epilog="exit: 0 ok | 2 gate rejected | 3 validation failed | 1 error | 64 usage",
    )
    sub = parser.add_subparsers(dest="command")

    p_scan = sub.add_parser("scan", help="approved tasks with gate verdict")
    p_scan.add_argument("--story")
    p_scan.add_argument("--project")
    p_scan.add_argument("--jql")
    p_scan.add_argument("--config")

    p_read = sub.add_parser("read", help="gate verdict + task content")
    p_read.add_argument("task_key")
    p_read.add_argument("--config")

    p_val = sub.add_parser("validate", help="offline structure/coverage check")
    p_val.add_argument("task_key")
    p_val.add_argument("testcases_file")
    p_val.add_argument("--config")

    p_cr = sub.add_parser("create", help="validate, gate, build xlsx, save locally, attach")
    p_cr.add_argument("task_key")
    p_cr.add_argument("testcases_file")
    p_cr.add_argument("--dry-run", action="store_true")
    p_cr.add_argument("--output-dir", help="directory to save the xlsx locally (default: current dir)")
    p_cr.add_argument("--config")

    args = parser.parse_args()
    if not args.command:
        parser.print_help()
        sys.exit(EXIT_USAGE)

    config_path = Path(
        args.config or ".claude/skills/test-case-agent/test-case-agent.config.json"
    )
    cfg = Config.load(config_path)

    if args.command == "scan":
        sys.exit(cmd_scan(
            Jira.from_env(), cfg,
            story=args.story, project=args.project, jql_override=args.jql,
        ))
    elif args.command == "read":
        sys.exit(cmd_read(Jira.from_env(), cfg, args.task_key))
    elif args.command == "validate":
        sys.exit(cmd_validate(cfg, args.task_key, Path(args.testcases_file)))
    elif args.command == "create":
        out_dir = Path(args.output_dir) if args.output_dir else None
        sys.exit(cmd_create(cfg, args.task_key, Path(args.testcases_file), args.dry_run, out_dir))


if __name__ == "__main__":
    try:
        main()
    except SystemExit:
        raise
    except Exception as e:
        print(f"error: {e}", file=sys.stderr)
        sys.exit(EXIT_ERROR)
