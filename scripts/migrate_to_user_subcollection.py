#!/usr/bin/env python3
"""
Idempotently copies all documents from the legacy root `media_items` collection into
`users/{userKey}/media_items` in Google Cloud Firestore, preserving exact document IDs
and all fields (`description`, `coverObjectPath`, `attributes`, `addedAt`, etc.).

Also writes a concrete parent document at `users/{userKey}` so the user document appears
as a non-virtual document in the GCP Firestore Console.
"""

import datetime
import json
import os
import sys
import urllib.parse
import urllib.request

PROJECT_ID = "project-f2e57f3d-860e-486f-a44"
SOURCE_COLLECTION = "media_items"
USERS_COLLECTION = "users"
TARGET_SUBCOLLECTION = "media_items"
DEFAULT_OWNER = "joshwever@gmail.com"
BATCH_SIZE = 400


def get_adc_token() -> str:
    adc_path = os.path.expanduser("~/.config/gcloud/application_default_credentials.json")
    with open(adc_path, "r", encoding="utf-8") as f:
        creds = json.load(f)
    payload = urllib.parse.urlencode({
        "client_id": creds["client_id"],
        "client_secret": creds["client_secret"],
        "refresh_token": creds["refresh_token"],
        "grant_type": "refresh_token",
    }).encode("utf-8")
    req = urllib.request.Request(
        "https://oauth2.googleapis.com/token",
        data=payload,
        headers={"Content-Type": "application/x-www-form-urlencoded"},
        method="POST",
    )
    with urllib.request.urlopen(req, timeout=15) as resp:
        return json.loads(resp.read().decode("utf-8"))["access_token"]


def list_all_documents(token: str, collection_path: str) -> list[dict]:
    docs: list[dict] = []
    page_token = None
    encoded_path = "/".join(urllib.parse.quote(seg, safe="") for seg in collection_path.split("/"))
    base_url = f"https://firestore.googleapis.com/v1/projects/{PROJECT_ID}/databases/(default)/documents/{encoded_path}"
    headers = {
        "Authorization": f"Bearer {token}",
        "x-goog-user-project": PROJECT_ID,
    }

    while True:
        url = f"{base_url}?pageSize=300"
        if page_token:
            url += f"&pageToken={urllib.parse.quote(page_token)}"
        req = urllib.request.Request(url, headers=headers)
        with urllib.request.urlopen(req, timeout=30) as resp:
            data = json.loads(resp.read().decode("utf-8"))
        docs.extend(data.get("documents", []))
        page_token = data.get("nextPageToken")
        if not page_token:
            break
    return docs


def commit_writes(token: str, writes: list[dict]) -> None:
    if not writes:
        return
    commit_url = f"https://firestore.googleapis.com/v1/projects/{PROJECT_ID}/databases/(default)/documents:commit"
    body = json.dumps({"writes": writes}).encode("utf-8")
    req = urllib.request.Request(
        commit_url,
        data=body,
        headers={
            "Authorization": f"Bearer {token}",
            "Content-Type": "application/json",
            "x-goog-user-project": PROJECT_ID,
        },
        method="POST",
    )
    with urllib.request.urlopen(req, timeout=45) as resp:
        if resp.status != 200:
            raise RuntimeError(f"Firestore commit failed with HTTP {resp.status}")


def main() -> None:
    owner_key = (sys.argv[1] if len(sys.argv) > 1 else DEFAULT_OWNER).strip().lower()
    token = get_adc_token()

    print(f"Reading legacy root collection '{SOURCE_COLLECTION}'...", flush=True)
    source_docs = list_all_documents(token, SOURCE_COLLECTION)
    print(f"Found {len(source_docs)} documents in root '{SOURCE_COLLECTION}'.", flush=True)

    now_iso = datetime.datetime.now(datetime.timezone.utc).strftime("%Y-%m-%dT%H:%M:%SZ")
    user_doc_name = (
        f"projects/{PROJECT_ID}/databases/(default)/documents/{USERS_COLLECTION}/{owner_key}"
    )
    commit_writes(token, [{
        "update": {
            "name": user_doc_name,
            "fields": {
                "email": {"stringValue": owner_key},
                "migratedAt": {"timestampValue": now_iso},
            },
        }
    }])
    print(f"Upserted parent user document '{USERS_COLLECTION}/{owner_key}'.", flush=True)

    migrated = 0
    for i in range(0, len(source_docs), BATCH_SIZE):
        chunk = source_docs[i : i + BATCH_SIZE]
        writes = []
        for doc in chunk:
            doc_id = doc["name"].rsplit("/", 1)[-1]
            target_name = f"{user_doc_name}/{TARGET_SUBCOLLECTION}/{doc_id}"
            writes.append({
                "update": {
                    "name": target_name,
                    "fields": doc.get("fields", {}),
                }
            })
        commit_writes(token, writes)
        migrated += len(writes)
        print(
            f"Committed {migrated}/{len(source_docs)} documents -> {USERS_COLLECTION}/{owner_key}/{TARGET_SUBCOLLECTION}",
            flush=True,
        )

    target_path = f"{USERS_COLLECTION}/{owner_key}/{TARGET_SUBCOLLECTION}"
    target_docs = list_all_documents(token, target_path)
    print(
        f"Verification complete: source={len(source_docs)} docs, target('{target_path}')={len(target_docs)} docs.",
        flush=True,
    )
    if len(target_docs) < len(source_docs):
        raise SystemExit("ERROR: Target document count is smaller than source document count!")


if __name__ == "__main__":
    main()
