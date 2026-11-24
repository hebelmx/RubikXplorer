"""Local review server for the label spike: serves the worktree and saves decisions (127.0.0.1 only)."""
import json, sys
from http.server import SimpleHTTPRequestHandler, ThreadingHTTPServer
from pathlib import Path
from functools import partial

ROOT = Path(sys.argv[1]).resolve()
DECISIONS = ROOT / "tools/label-audit/artifacts/label-spike/decisions.json"


class Handler(SimpleHTTPRequestHandler):
    def do_GET(self):
        if self.path == "/decisions":
            data = DECISIONS.read_text() if DECISIONS.is_file() else "{}"
            return self._send(200, data)
        return super().do_GET()

    def do_POST(self):
        if self.path != "/decisions":
            return self._send(404, "{}")
        body = self.rfile.read(int(self.headers.get("Content-Length", 0)))
        data = json.loads(body)
        if not isinstance(data, dict):
            return self._send(400, "{}")
        tmp = DECISIONS.with_suffix(".tmp")
        tmp.write_text(json.dumps(data, indent=1, sort_keys=True))
        tmp.replace(DECISIONS)
        return self._send(200, json.dumps({"saved": len(data)}))

    def _send(self, code, text):
        raw = text.encode()
        self.send_response(code)
        self.send_header("Content-Type", "application/json")
        self.send_header("Content-Length", str(len(raw)))
        self.end_headers()
        self.wfile.write(raw)

    def log_message(self, *args):
        pass


ThreadingHTTPServer(("127.0.0.1", int(sys.argv[2])), partial(Handler, directory=str(ROOT))).serve_forever()
