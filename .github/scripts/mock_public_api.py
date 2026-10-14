"""Local, empty public API used only by CI builds; no production credentials."""
import json
from urllib.parse import urlsplit
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer

class Handler(BaseHTTPRequestHandler):
    def do_GET(self):
        path = urlsplit(self.path).path
        payload = [] if path == "/api/v1/public/tags/" else {"count": 0, "next": None, "previous": None, "results": []}
        body = json.dumps(payload).encode()
        self.send_response(200 if path in {"/api/v1/public/posts/", "/api/v1/public/tags/"} else 404)
        self.send_header("Content-Type", "application/json")
        self.send_header("Content-Length", str(len(body)))
        self.end_headers()
        self.wfile.write(body)

    def log_message(self, *args):
        pass

if __name__ == "__main__":
    ThreadingHTTPServer(("127.0.0.1", 8767), Handler).serve_forever()
