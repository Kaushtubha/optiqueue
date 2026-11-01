#!/usr/bin/env python3
"""
OptiQueue Synthetic Load & Throughput Simulation Utility

Generates concurrent background job submissions across multiple priority tiers
to measure API ingestion latency, queue responsiveness, and rate-limiting behaviors.

Usage:
    python scripts/simulate_load.py --url http://localhost:8080 --jobs 100 --concurrency 10
"""

import argparse
import concurrent.futures
import json
import random
import sys
import time
import urllib.request
import urllib.error
import uuid

JOB_TYPES = ["EMAIL_SEND", "WEBHOOK_DISPATCH", "REPORT_GEN"]

def submit_single_job(base_url: str, api_key: str = None, timeout: float = 10.0) -> float:
    url = f"{base_url.rstrip('/')}/api/jobs"
    headers = {
        "Content-Type": "application/json",
        "Accept": "application/json"
    }
    if api_key:
        headers["X-API-KEY"] = api_key

    payload = {
        "jobType": random.choice(JOB_TYPES),
        "payload": json.dumps({"traceId": str(uuid.uuid4()), "timestamp": time.time()}),
        "priority": random.randint(1, 4),
        "idempotencyKey": f"sim-{uuid.uuid4()}"
    }

    data = json.dumps(payload).encode("utf-8")
    req = urllib.request.Request(url, data=data, headers=headers, method="POST")

    start = time.perf_counter()
    try:
        with urllib.request.urlopen(req, timeout=timeout) as resp:
            elapsed_ms = (time.perf_counter() - start) * 1000.0
            if resp.status in (200, 201):
                return elapsed_ms
            return -1.0
    except Exception:
        return -1.0

def run_simulation(base_url: str, total_jobs: int, concurrency: int, api_key: str = None):
    print(f"[*] Starting simulation: {total_jobs} jobs, concurrency={concurrency} -> {base_url}")
    latencies = []
    failures = 0

    start_all = time.perf_counter()
    with concurrent.futures.ThreadPoolExecutor(max_workers=concurrency) as executor:
        futures = [executor.submit(submit_single_job, base_url, api_key) for _ in range(total_jobs)]
        for f in concurrent.futures.as_completed(futures):
            res = f.result()
            if res > 0:
                latencies.append(res)
            else:
                failures += 1

    total_time = time.perf_counter() - start_all
    throughput = len(latencies) / total_time if total_time > 0 else 0

    latencies.sort()
    p50 = latencies[int(len(latencies) * 0.50)] if latencies else 0
    p95 = latencies[int(len(latencies) * 0.95)] if latencies else 0
    p99 = latencies[int(len(latencies) * 0.99)] if latencies else 0

    print("\n--- Benchmark Results ---")
    print(f"  Total Duration:     {total_time:.2f}s")
    print(f"  Successful Jobs:    {len(latencies)} / {total_jobs}")
    print(f"  Failures:           {failures}")
    print(f"  Throughput:         {throughput:.1f} jobs/sec")
    print(f"  Latency P50:        {p50:.1f}ms")
    print(f"  Latency P95:        {p95:.1f}ms")
    print(f"  Latency P99:        {p99:.1f}ms")

def main():
    parser = argparse.ArgumentParser(description="OptiQueue Load Simulation Utility")
    parser.add_argument("--url", default="http://localhost:8080", help="Base URL of OptiQueue instance")
    parser.add_argument("--jobs", type=int, default=50, help="Total number of jobs to submit")
    parser.add_argument("--concurrency", type=int, default=5, help="Number of concurrent worker threads")
    parser.add_argument("--api-key", default=None, help="API Key for authentication")

    args = parser.parse_args()
    run_simulation(args.url, args.jobs, args.concurrency, args.api_key)

if __name__ == "__main__":
    main()
