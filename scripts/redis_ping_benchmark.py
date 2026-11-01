#!/usr/bin/env python3
"""
OptiQueue Redis Latency & Heartbeat Connectivity Benchmark Utility
"""

import argparse
import socket
import sys
import time

def ping_redis(host: str, port: int, iterations: int = 20):
    print(f"[*] Testing Redis connection latency to {host}:{port} ({iterations} pings)...")
    latencies = []

    for i in range(iterations):
        t0 = time.perf_counter()
        try:
            with socket.create_connection((host, port), timeout=2.0) as s:
                s.sendall(b"*1\r\n$4\r\nPING\r\n")
                resp = s.recv(1024)
                if b"+PONG" in resp:
                    dt = (time.perf_counter() - t0) * 1000.0
                    latencies.append(dt)
        except Exception as e:
            print(f"[!] Ping {i+1} failed: {e}")

        time.sleep(0.05)

    if latencies:
        print(f"[+] Min: {min(latencies):.2f}ms | Avg: {sum(latencies)/len(latencies):.2f}ms | Max: {max(latencies):.2f}ms")
    else:
        print("[!] No successful pings.")

if __name__ == "__main__":
    parser = argparse.ArgumentParser()
    parser.add_argument("--host", default="localhost")
    parser.add_argument("--port", type=int, default=6379)
    parser.add_argument("--count", type=int, default=10)
    args = parser.parse_args()
    ping_redis(args.host, args.port, args.count)
