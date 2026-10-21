
#!/usr/bin/env python3
"""Diagnose Zoho Sprints OAuth, team, project, epic, and feed access.

This is a read-only diagnostic script. It does not create or modify records.
"""

import json
import os
import urllib.error
import urllib.parse
import urllib.request
from pathlib import Path

SCRIPT_DIR = Path(__file__).resolve().parent
ENV_FILE = SCRIPT_DIR / ".env"


def load_env():
    """Load .env without printing secrets or overriding existing variables."""
    if not ENV_FILE.exists():
        return

    for line in ENV_FILE.read_text(encoding="utf-8").splitlines():
        line = line.strip()

        if not line or line.startswith("#") or "=" not in line:
            continue

        key, value = line.split("=", 1)
        os.environ.setdefault(
            key.strip(),
            value.strip().strip("\"'")
        )


load_env()

DOMAIN = os.getenv("ZOHO_DOMAIN", "com")
CLIENT_ID = os.getenv("ZOHO_CLIENT_ID")
CLIENT_SECRET = os.getenv("ZOHO_CLIENT_SECRET")
REFRESH_TOKEN = os.getenv("ZOHO_REFRESH_TOKEN")
TEAM_ID = os.getenv("ZOHO_TEAM_ID")
PROJECT_ID = os.getenv("ZOHO_PROJECT_ID")
EPIC_ID = os.getenv("ZOHO_EPIC_ID")

BASE_URL = f"https://sprintsapi.zoho.{DOMAIN}/zsapi"
TOKEN_URL = f"https://accounts.zoho.{DOMAIN}/oauth/v2/token"


def request_json(url, headers=None, form=None):
    """Make an HTTP GET or POST and parse its JSON response."""
    data = urllib.parse.urlencode(form).encode("utf-8") if form else None

    req = urllib.request.Request(
        url,
        data=data,
        headers=headers or {"Accept": "application/json"},
        method="POST" if data else "GET",
    )

    try:
        with urllib.request.urlopen(req, timeout=30) as response:
            raw = response.read().decode("utf-8", "replace")

            try:
                body = json.loads(raw)
            except json.JSONDecodeError:
                body = {"raw": raw[:1000]}

            return response.status, body

    except urllib.error.HTTPError as exc:
        raw = exc.read().decode("utf-8", "replace")

        try:
            body = json.loads(raw)
        except json.JSONDecodeError:
            body = {"raw": raw[:1000]}

        return exc.code, body

    except urllib.error.URLError as exc:
        return None, {"network_error": str(exc.reason)}


def print_result(name, status, body):
    """Print response diagnostics without exposing credentials."""
    print(f"\n--- {name} ---")
    print("HTTP status:", status if status is not None else "NETWORK ERROR")

    if isinstance(body, dict):
        for key in ("code", "message", "status", "error", "network_error"):
            if key in body:
                print(f"{key}: {body[key]}")

        if not any(
            key in body
            for key in ("code", "message", "status", "error", "network_error")
        ):
            print("Response keys:", list(body.keys())[:30])
            print("Response:", json.dumps(body, ensure_ascii=False)[:2000])
    else:
        print("Response:", str(body)[:2000])


def main():
    required = {
        "ZOHO_CLIENT_ID": CLIENT_ID,
        "ZOHO_CLIENT_SECRET": CLIENT_SECRET,
        "ZOHO_REFRESH_TOKEN": REFRESH_TOKEN,
        "ZOHO_TEAM_ID": TEAM_ID,
    }

    missing = [key for key, value in required.items() if not value]

    if missing:
        print("Missing configuration:", ", ".join(missing))
        print("Check the .env file in:", SCRIPT_DIR)
        print("Do not paste secrets into chat.")
        return

    print("Zoho domain:", DOMAIN)
    print("Token endpoint:", TOKEN_URL)
    print("Credentials loaded: yes (values hidden)")

    # 1. Refresh OAuth access token.
    token_status, token_data = request_json(
        TOKEN_URL,
        form={
            "grant_type": "refresh_token",
            "client_id": CLIENT_ID,
            "client_secret": CLIENT_SECRET,
            "refresh_token": REFRESH_TOKEN,
        },
    )

    if (
        token_status != 200
        or not isinstance(token_data, dict)
        or not token_data.get("access_token")
    ):
        print_result("OAuth token refresh", token_status, token_data)
        print("Token refresh failed. Check the error above.")
        return

    access_token = token_data["access_token"]
    print("\nOAuth token refresh: SUCCESS (token hidden)")

    headers = {
        "Authorization": f"Zoho-oauthtoken {access_token}",
        "Accept": "application/json",
    }

    # 2. Test Teams API.
    status, body = request_json(
        f"{BASE_URL}/teams/",
        headers=headers,
    )
    print_result("Teams API: GET /teams/", status, body)

    if not PROJECT_ID:
        print("\nProject tests skipped: set ZOHO_PROJECT_ID in .env")
        print("\nDiagnostic completed. No records were created or modified.")
        return

    team = urllib.parse.quote(TEAM_ID, safe="")
    project = urllib.parse.quote(PROJECT_ID, safe="")

    # 3. Test project details.
    project_url = (
        f"{BASE_URL}/team/{team}/projects/{project}/?action=details"
    )
    status, body = request_json(project_url, headers=headers)
    print_result("Project details API", status, body)

    # 4. Test epic details using the documented endpoint.
    if EPIC_ID:
        epic = urllib.parse.quote(EPIC_ID, safe="")
        epic_url = (
            f"{BASE_URL}/team/{team}/projects/{project}"
            f"/epic/{epic}/?action=details"
        )
        status, body = request_json(epic_url, headers=headers)
        print_result("Epic details API", status, body)
    else:
        print("\nEpic details test skipped: set ZOHO_EPIC_ID in .env")

    # 5. Test the candidate feed endpoint for comparison.
    feed_url = (
        f"{BASE_URL}/team/{team}/projects/{project}/feed/"
    )
    status, body = request_json(feed_url, headers=headers)
    print_result("Project feed API (candidate endpoint)", status, body)

    print("\nDiagnostic completed.")
    print("No Zoho records were created or modified.")


if __name__ == "__main__":
    main()
