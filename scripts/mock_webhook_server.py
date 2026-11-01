#!/usr/bin/env python3
"""
OptiQueue Mock Webhook Receiver HTTP Server

Used during development and load testing to simulate external endpoint behaviors,
including controllable latency and failure rates.
"""

from http.server import HTTPServer, BaseHTTPRequestHandler
import sys
import time

class WebhookHandler(BaseHTTPRequestHandler):
    def do_POST(self):
        length = int(self.headers.get("Content-Length", 0))
        body = self.rfile.read(length)
        print(f"[*] Received Webhook: {self.path} (Bytes: {length})")
        self.send_response(200)
        self.send_header("Content-Type", "application/json")
        self.end_headers()
        self.wfile.write(b'{"status":"ACK"}')

def run(port=9090):
    print(f"[*] Starting mock webhook receiver on port {port}...")
    server = HTTPServer(("0.0.0.0", port), WebhookHandler)
    try:
        server.serve_forever()
    except KeyboardInterrupt:
        print("\n[*] Stopping server.")

if __name__ == "__main__":
    port = int(sys.argv[1]) if len(sys.argv) > 1 else 9090
    run(port)
