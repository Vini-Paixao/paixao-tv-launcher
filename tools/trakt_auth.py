"""Signs in to Trakt once with PKCE and saves the token to a file, never to the screen.

Usage: python3 tools/trakt_auth.py <client id> trakt-token.json
The Trakt app's redirect URI must be http://localhost:8765/callback.
"""
import base64
import hashlib
import json
import pathlib
import secrets
import sys
import urllib.parse
import urllib.request
from http.server import BaseHTTPRequestHandler, HTTPServer

CLIENT_ID = sys.argv[1]
OUT = pathlib.Path(sys.argv[2])
REDIRECT = "http://localhost:8765/callback"
verifier = secrets.token_urlsafe(64)
challenge = base64.urlsafe_b64encode(hashlib.sha256(verifier.encode()).digest()).rstrip(b"=").decode()
state = secrets.token_urlsafe(16)


def exchange(code):
    body = json.dumps({"code": code, "client_id": CLIENT_ID, "code_verifier": verifier, "redirect_uri": REDIRECT,
                       "grant_type": "authorization_code"}).encode()
    request = urllib.request.Request("https://api.trakt.tv/oauth/token", body, {
        "Content-Type": "application/json", "User-Agent": "PaixaoTV/0.1", "trakt-api-version": "2",
        "trakt-api-key": CLIENT_ID})
    return urllib.request.urlopen(request, timeout=20).read()


class Handler(BaseHTTPRequestHandler):
    def do_GET(self):
        query = urllib.parse.parse_qs(urllib.parse.urlparse(self.path).query)
        if "code" not in query or query.get("state", [""])[0] != state:
            self.send_error(400)
            return
        OUT.write_bytes(exchange(query["code"][0]))
        print(f"Token saved to {OUT}")
        self.send_response(200)
        self.send_header("Content-Type", "text/html; charset=utf-8")
        self.end_headers()
        self.wfile.write("<h1>Connected to Trakt. You can close this tab.</h1>".encode())

    def log_message(self, *args):
        pass


server = HTTPServer(("127.0.0.1", 8765), Handler)
server.timeout = 900
print("Open this link and approve:\n")
print("https://auth.trakt.tv/oauth/authorize?" + urllib.parse.urlencode({
    "response_type": "code", "client_id": CLIENT_ID, "redirect_uri": REDIRECT, "state": state,
    "code_challenge": challenge, "code_challenge_method": "S256"}))
server.handle_request()
