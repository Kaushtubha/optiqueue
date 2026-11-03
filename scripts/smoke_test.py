#!/usr/bin/env python3
"""
OptiQueue End-to-End Smoke Test Script

Submits a synthetic job, polls for lifecycle status transitions,
and verifies final COMPLETED state.
"""

import argparse
import json
import sys
import time
import urllib.request
import uuid

def run_smoke_test(base_url: str, timeout: int = 15):
    print(f"[*] Starting OptiQueue Smoke Test against {base_url}...")
    submit_url = f"{base_url.rstrip('/')}/api/jobs"

    key = f"smoke-{uuid.uuid4()}"
    payload = {
        "jobType": "EMAIL_SEND",
        "payload": json.dumps({"recipient": "smoke-test@optiqueue.internal"}),
        "priority": 1,
        "idempotencyKey": key
    }

    req = urllib.request.Request(
        submit_url,
        data=json.dumps(payload).encode("utf-8"),
        headers={"Content-Type": "application/json", "Accept": "application/json"},
        method="POST"
    )

    try:
        with urllib.request.urlopen(req) as resp:
            data = json.loads(resp.read().decode())
            job_id = data.get("id")
            print(f"[+] Job submitted successfully with ID: {job_id}")
    except Exception as e:
        print(f"[!] Job submission failed: {e}")
        return False

    poll_url = f"{base_url.rstrip('/')}/api/jobs/{job_id}"
    start = time.time()
    while time.time() - start < timeout:
        try:
            with urllib.request.urlopen(urllib.request.Request(poll_url, headers={"Accept": "application/json"})) as resp:
                status_data = json.loads(resp.read().decode())
                current_status = status_data.get("status")
                print(f"[*] Job {job_id} status: {current_status}")
                if current_status == "COMPLETED":
                    print("[+] Smoke test PASSED: Job completed successfully.")
                    return True
                elif current_status in ("FAILED", "DEAD_LETTER", "CANCELLED"):
                    print(f"[!] Smoke test FAILED: Unexpected terminal status {current_status}")
                    return False
        except Exception as e:
            print(f"[!] Polling error: {e}")

        time.sleep(1.0)

    print("[!] Smoke test TIMED OUT waiting for completion.")
    return False

if __name__ == "__main__":
    parser = argparse.ArgumentParser()
    parser.add_argument("--url", default="http://localhost:8080")
    args = parser.parse_args()
    success = run_smoke_test(args.url)
    sys.exit(0 if success else 1)
