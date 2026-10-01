#!/usr/bin/env python3
"""Local deterministic GitHub/Stack Overflow API fixture; never contacts real APIs."""
import json
import sys
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer

version = 1


class Handler(BaseHTTPRequestHandler):
    def do_GET(self):
        if version == 3:
            self.respond(503, {"message": "fixture unavailable"})
        elif self.path.startswith("/repos/"):
            self.respond(200, {"name": "Fixture repository", "updated_at": f"2026-01-0{version}T00:00:00Z",
                               "description": "A deterministic update", "owner": {"login": "fixture"}})
        elif self.path.startswith("/questions/"):
            self.respond(200, {"items": [{"title": "Fixture question", "last_activity_date": 1767225600}]})
        else:
            self.respond(404, {})

    def do_POST(self):
        global version
        if self.path != "/control":
            return self.respond(404, {})
        data = json.loads(self.rfile.read(int(self.headers["Content-Length"])))
        version = data["version"]
        self.respond(200, {"version": version})

    def respond(self, status, body):
        data = json.dumps(body).encode()
        self.send_response(status)
        self.send_header("Content-Type", "application/json")
        self.send_header("Content-Length", str(len(data)))
        self.end_headers()
        self.wfile.write(data)

    def log_message(self, *args):
        pass


server = ThreadingHTTPServer(("127.0.0.1", int(sys.argv[1])), Handler)
server.serve_forever()
