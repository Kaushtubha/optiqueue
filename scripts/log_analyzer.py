#!/usr/bin/env python3
"""
OptiQueue Log Triage & Pattern Analysis Script

Scans OptiQueue log files to summarize error rates, failover detections,
and slow-executing job handlers.
"""

import re
import sys

def analyze_logs(logfile: str):
    print(f"[*] Analyzing log file: {logfile}")
    error_count = 0
    failover_count = 0

    try:
        with open(logfile, "r", encoding="utf-8", errors="ignore") as f:
            for line in f:
                if "ERROR" in line:
                    error_count += 1
                if "Failover scan:" in line:
                    failover_count += 1

        print(f"[+] Total Errors: {error_count}")
        print(f"[+] Failovers Detected: {failover_count}")
    except Exception as e:
        print(f"[!] Log analysis error: {e}")

if __name__ == "__main__":
    path = sys.argv[1] if len(sys.argv) > 1 else "app.log"
    analyze_logs(path)
