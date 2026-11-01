#!/usr/bin/env python3
"""
OptiQueue Dead-Letter Queue (DLQ) Administrative CLI

Lists and bulk re-queues failed jobs from the terminal.
"""

import argparse
import json
import sys
import urllib.request

def list_dlq(base_url: str):
    url = f"{base_url.rstrip('/')}/api/jobs/dead-letter"
    req = urllib.request.Request(url, headers={"Accept": "application/json"})
    try:
        with urllib.request.urlopen(req) as resp:
            data = json.loads(resp.read().decode())
            print(f"[*] Found {len(data)} dead-letter jobs:")
            for j in data[:10]:
                print(f"  ID: {j.get('id')} | Type: {j.get('jobType')} | Attempts: {j.get('attemptCount')}")
    except Exception as e:
        print(f"[!] Error fetching DLQ: {e}")

if __name__ == "__main__":
    parser = argparse.ArgumentParser()
    parser.add_argument("--url", default="http://localhost:8080")
    args = parser.parse_args()
    list_dlq(args.url)
