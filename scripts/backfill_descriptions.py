#!/usr/bin/env python3
"""
Backfills brief descriptions from the OpenLibrary API for all BOOK and EBOOK items
in Firestore (`media_items`) that do not yet have a `description` field.

Features:
- Processes BOOK items first (using ISBN -> /works/{id}.json, falling back to title/author search),
  then processes EBOOK items (using title + author search -> /works/{id}.json or first_sentence).
- Commits updates to Firestore in safe batches of 50 using `updateMask.fieldPaths=description`
  so existing fields (coverObjectPath, attributes, etc.) are never overwritten.
- Idempotent & resumable: skips any document that already has a non-empty description.
"""

import concurrent.futures
import json
import re
import subprocess
import sys
import time
import urllib.parse
import urllib.request

PROJECT_ID = "project-f2e57f3d-860e-486f-a44"
COLLECTION = "media_items"
USER_AGENT = "StackAndSpine/1.0 (https://stack-and-spine-212550439760.us-central1.run.app)"
MAX_WORKERS = 10
BATCH_SIZE = 50


def get_adc_token() -> str:
    return subprocess.check_output(
        ["/home/jwever/google-cloud-sdk/bin/gcloud", "auth", "application-default", "print-access-token"],
        text=True,
    ).strip()


def http_get_json(url: str, headers: dict | None = None, timeout: int = 10) -> dict | None:
    req_headers = {"User-Agent": USER_AGENT}
    if headers:
        req_headers.update(headers)
    req = urllib.request.Request(url, headers=req_headers)
    try:
        with urllib.request.urlopen(req, timeout=timeout) as resp:
            if resp.status != 200:
                return None
            return json.loads(resp.read().decode("utf-8"))
    except Exception:
        return None


def list_firestore_items(token: str, refresh_truncated: bool = False) -> list[dict]:
    items = []
    page_token = None
    base_url = f"https://firestore.googleapis.com/v1/projects/{PROJECT_ID}/databases/(default)/documents/{COLLECTION}"
    headers = {
        "Authorization": f"Bearer {token}",
        "x-goog-user-project": PROJECT_ID,
    }

    while True:
        url = f"{base_url}?pageSize=300"
        if page_token:
            url += f"&pageToken={urllib.parse.quote(page_token)}"
        data = http_get_json(url, headers=headers, timeout=30)
        if not data:
            break
        for doc in data.get("documents", []):
            fields = doc.get("fields", {})
            media_type = fields.get("mediaType", {}).get("stringValue", "")
            existing_desc = (fields.get("description", {}).get("stringValue", "") or "").strip()
            if media_type not in ("BOOK", "EBOOK"):
                continue
            if refresh_truncated:
                if not existing_desc.endswith("..."):
                    continue
            else:
                if existing_desc:
                    continue
            items.append({
                "name": doc["name"],
                "id": fields.get("id", {}).get("stringValue", doc["name"].rsplit("/", 1)[-1]),
                "mediaType": media_type,
                "title": fields.get("title", {}).get("stringValue", ""),
                "creator": fields.get("creator", {}).get("stringValue", ""),
                "isbn": fields.get("identifierCode", {}).get("stringValue", ""),
            })
        page_token = data.get("nextPageToken")
        if not page_token:
            break
    return items


def clean_description(raw: str) -> str:
    # Strip markdown links [text](url) -> text and excess whitespace
    text = re.sub(r"\[([^\]]+)\]\([^)]+\)", r"\1", raw)
    text = re.sub(r"https?://\S+", "", text)
    text = re.sub(r"\s+", " ", text).strip()
    if len(text) <= 2000:
        return text
    cut = text.rfind(" ", 0, 1997)
    return (text[:cut] if cut > 1000 else text[:1997]) + "..."


def extract_work_description(work_key: str) -> str | None:
    if not work_key or not work_key.startswith("/works/"):
        return None
    work = http_get_json(f"https://openlibrary.org{work_key}.json", timeout=8)
    if not work:
        return None
    desc = work.get("description")
    if isinstance(desc, str) and desc.strip():
        return clean_description(desc)
    if isinstance(desc, dict) and isinstance(desc.get("value"), str) and desc["value"].strip():
        return clean_description(desc["value"])
    return None


def clean_search_title(title: str) -> str:
    # Strip series parentheticals like "(Dray Prescot #21)" or "[Series 1]" for better OpenLibrary matches
    t = re.sub(r"\s*[\(\[][^\)\]]*[\)\]]", "", title)
    t = re.sub(r"\s*-\s*Author-Signed.*$", "", t, flags=re.IGNORECASE)
    return t.strip() or title.strip()


def lookup_description(item: dict) -> tuple[dict, str | None]:
    # 1. Try direct ISBN -> Work lookup first (fastest & most accurate for Books)
    clean_isbn = re.sub(r"[^0-9Xx]", "", item.get("isbn") or "")
    if len(clean_isbn) in (10, 13):
        edition = http_get_json(f"https://openlibrary.org/isbn/{clean_isbn}.json", timeout=8)
        if edition:
            works = edition.get("works") or []
            if works and isinstance(works[0], dict):
                desc = extract_work_description(works[0].get("key", ""))
                if desc:
                    return item, desc

    # 2. Fall back to OpenLibrary Search API by cleaned title + author
    title = clean_search_title(item["title"])
    author = (item.get("creator") or "").strip()
    params = {
        "title": title,
        "limit": "1",
        "fields": "key,first_sentence",
    }
    if author and author.lower() != "unknown author":
        params["author"] = author.split("&")[0].split(",")[0].strip()

    url = "https://openlibrary.org/search.json?" + urllib.parse.urlencode(params)
    search = http_get_json(url, timeout=10)
    docs = (search or {}).get("docs") or []

    # Retry title-only if author filter was too strict
    if not docs and "author" in params:
        params.pop("author")
        url = "https://openlibrary.org/search.json?" + urllib.parse.urlencode(params)
        search = http_get_json(url, timeout=10)
        docs = (search or {}).get("docs") or []

    if docs:
        doc = docs[0]
        desc = extract_work_description(doc.get("key", ""))
        if desc:
            return item, desc
        first_sentence = doc.get("first_sentence")
        if isinstance(first_sentence, list) and first_sentence and isinstance(first_sentence[0], str):
            return item, clean_description(first_sentence[0])
        if isinstance(first_sentence, str) and first_sentence.strip():
            return item, clean_description(first_sentence)

    return item, None


def commit_descriptions(token: str, updates: list[tuple[dict, str]]) -> None:
    if not updates:
        return
    commit_url = f"https://firestore.googleapis.com/v1/projects/{PROJECT_ID}/databases/(default)/documents:commit"
    writes = []
    for item, desc in updates:
        writes.append({
            "update": {
                "name": item["name"],
                "fields": {
                    "description": {"stringValue": desc},
                },
            },
            "updateMask": {
                "fieldPaths": ["description"],
            },
        })
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
    with urllib.request.urlopen(req, timeout=30) as resp:
        if resp.status != 200:
            raise RuntimeError(f"Firestore commit failed: HTTP {resp.status}")


def main() -> None:
    args = sys.argv[1:]
    refresh_truncated = "--refresh-truncated" in args
    type_args = [a.upper() for a in args if not a.startswith("--")]
    only_type = type_args[0] if type_args else None

    token = get_adc_token()
    token_time = time.time()

    mode_label = "truncated (ending in '...')" if refresh_truncated else "missing descriptions"
    print(f"Listing Firestore BOOK and EBOOK items ({mode_label})...", flush=True)
    items = list_firestore_items(token, refresh_truncated=refresh_truncated)
    if only_type:
        items = [i for i in items if i["mediaType"] == only_type]
    else:
        # Process BOOK items first, then EBOOK items
        items.sort(key=lambda i: (0 if i["mediaType"] == "BOOK" else 1, i["title"].lower()))

    books_count = sum(1 for i in items if i["mediaType"] == "BOOK")
    ebooks_count = sum(1 for i in items if i["mediaType"] == "EBOOK")
    print(f"Found {len(items)} items to enrich ({books_count} BOOK, {ebooks_count} EBOOK).", flush=True)

    pending: list[tuple[dict, str]] = []
    processed = 0
    enriched = 0

    with concurrent.futures.ThreadPoolExecutor(max_workers=MAX_WORKERS) as executor:
        for i in range(0, len(items), BATCH_SIZE):
            chunk = items[i : i + BATCH_SIZE]
            # Refresh ADC token every 40 minutes for long runs
            if time.time() - token_time > 2400:
                token = get_adc_token()
                token_time = time.time()

            futures = [executor.submit(lookup_description, item) for item in chunk]
            for fut in concurrent.futures.as_completed(futures):
                item, desc = fut.result()
                processed += 1
                if desc:
                    pending.append((item, desc))
                    enriched += 1

            if pending:
                commit_descriptions(token, pending)
                pending.clear()

            print(f"Progress: {processed}/{len(items)} checked | {enriched} descriptions written to Firestore", flush=True)

    print(f"DONE! Checked {processed} items, populated {enriched} descriptions in Firestore.", flush=True)


if __name__ == "__main__":
    main()
