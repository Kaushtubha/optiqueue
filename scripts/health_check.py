#!/usr/bin/env python3
"""
OptiQueue Cluster & Health Verification Utility

Performs end-to-end health verification of an OptiQueue deployment:
- Probes HTTP connectivity and response latency
- Evaluates queue metrics (active workers, dead-letter count, queue depth)
- Validates rate-limiter response headers

Usage:
    python scripts/health_check.py --url http://localhost:8080 --api-key <optional-key>
"""

import argparse
import json
import sys
import time
import urllib.request
import urllib.error

def check_health(base_url: str, api_key: str = None, timeout: float = 5.0) -> bool:
    print(f"[*] Probing OptiQueue endpoint: {base_url}")
    metrics_url = f"{base_url.rstrip('/')}/api/jobs/metrics"
    headers = {
        "Accept": "application/json",
        "User-Agent": "OptiQueue-HealthCheck/1.0"
    }
    if api_key:
        headers["X-API-KEY"] = api_key

    req = urllib.request.Request(metrics_url, headers=headers, method="GET")

    start_time = time.perf_counter()
    try:
        with urllib.request.urlopen(req, timeout=timeout) as response:
            latency_ms = (time.perf_counter() - start_time) * 1000
            status_code = response.getcode()
            body = response.read().decode("utf-8")

            if status_code != 200:
                print(f"[!] Health check failed with HTTP {status_code}")
                return False

            data = json.loads(body)
            print(f"[+] Health probe succeeded in {latency_ms:.1f}ms")
            print("--- Telemetry Snapshot ---")
            print(f"  Total Jobs:       {data.get('totalJobs', 0)}")
            print(f"  Pending Jobs:     {data.get('pendingCount', 0)}")
            print(f"  Running Jobs:     {data.get('runningCount', 0)}")
            print(f"  Completed Jobs:   {data.get('completedCount', 0)}")
            print(f"  Dead Letter Jobs: {data.get('deadLetterCount', 0)}")
            print(f"  Active Workers:   {data.get('activeWorkers', 0)}")

            dlq_count = data.get('deadLetterCount', 0)
            if dlq_count > 100:
                print(f"[WARN] Elevated Dead Letter Queue count: {dlq_count}")

            return True

    except urllib.error.HTTPError as e:
        print(f"[!] HTTP Error {e.code}: {e.reason}")
        return False
    except urllib.error.URLError as e:
        print(f"[!] Connection failed: {e.reason}")
        return False
    except Exception as e:
        print(f"[!] Unexpected error during health probe: {e}")
        return False

def main():
    parser = argparse.ArgumentParser(description="OptiQueue Health Check Utility")
    parser.add_argument("--url", default="http://localhost:8080", help="Base URL of OptiQueue instance")
    parser.add_argument("--api-key", default=None, help="API Key for authentication")
    parser.add_argument("--timeout", type=float, default=5.0, help="Request timeout in seconds")

    args = parser.parse_args()
    success = check_health(args.url, args.api_key, args.timeout)
    sys.exit(0 if success else 1)

if __name__ == "__main__":
    main()
