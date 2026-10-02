#!/usr/bin/env python3
"""Controlled external API for isolated load/lifecycle checks. Never deploy this fixture."""
import json
import threading
import time
from collections import Counter
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer

lock = threading.Lock()
state = {'version': 1, 'delay': 0.0, 'active': 0, 'max_active': 0}
counts = Counter()

class Handler(BaseHTTPRequestHandler):
    protocol_version = 'HTTP/1.1'

    def do_GET(self):
        if self.path == '/status':
            with lock:
                result = dict(state, counts=dict(counts))
            return self.respond(200, result)
        with lock:
            version, delay = state['version'], state['delay']
            state['active'] += 1
            state['max_active'] = max(state['max_active'], state['active'])
            counts[self.path] += 1
        try:
            time.sleep(delay)
            if version == 3:
                self.respond(503, {'message': 'fixture unavailable'})
            elif self.path.startswith('/repos/'):
                self.respond(200, {'name': 'Fixture', 'updated_at': f'2026-01-{version:02}T00:00:00Z',
                                   'description': 'Controlled event', 'owner': {'login': 'fixture'}})
            elif self.path.startswith('/questions/'):
                self.respond(200, {'items': [{'title': 'Fixture', 'last_activity_date': 1767225600}]})
            else:
                self.respond(404, {})
        finally:
            with lock:
                state['active'] -= 1

    def do_POST(self):
        if self.path != '/control': return self.respond(404, {})
        body = json.loads(self.rfile.read(int(self.headers.get('Content-Length', '0'))))
        with lock:
            for key in ('version', 'delay'):
                if key in body: state[key] = body[key]
            if body.get('reset_counts'):
                counts.clear()
                state['max_active'] = state['active']
        self.respond(200, {'ok': True})

    def respond(self, status, body):
        data = json.dumps(body).encode()
        self.send_response(status)
        self.send_header('Content-Type', 'application/json')
        self.send_header('Content-Length', str(len(data)))
        self.end_headers()
        try: self.wfile.write(data)
        except (BrokenPipeError, ConnectionResetError): pass

    def log_message(self, *args): pass

ThreadingHTTPServer(('0.0.0.0', 8090), Handler).serve_forever()
