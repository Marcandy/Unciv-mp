"""Tiny client for the mpserver HTTP API (stdlib only)."""
import json
import os
import urllib.error
import urllib.request

BASE = os.environ.get("GAME_URL", "http://127.0.0.1:8090")
# Printed by the server at boot unless MP_ADMIN_TOKEN is set; needed to create games, autoplay and replay.
ADMIN = os.environ.get("MP_ADMIN_TOKEN")


def _req(method, path, body=None, token=None, raw=False, admin=False):
    data = json.dumps(body).encode() if body is not None else None
    req = urllib.request.Request(BASE + path, data=data, method=method)
    req.add_header("Content-Type", "application/json")
    if token:
        req.add_header("X-Seat-Token", token)
    if admin and ADMIN:
        req.add_header("X-Admin-Token", ADMIN)
    # Local server: bypass any configured HTTPS proxy.
    opener = urllib.request.build_opener(urllib.request.ProxyHandler({}))
    try:
        with opener.open(req, timeout=600) as r:
            payload = r.read().decode()
    except urllib.error.HTTPError as e:
        raise RuntimeError(f"{method} {path} -> {e.code}: {e.read().decode()[:500]}") from None
    return payload if raw else json.loads(payload)


class Seat:
    def __init__(self, game_id, civ, token):
        self.game_id, self.civ, self.token = game_id, civ, token

    def brief(self):
        return _req("GET", f"/games/{self.game_id}/brief/{self.civ}", token=self.token, raw=True)

    def view(self):
        return _req("GET", f"/games/{self.game_id}/view/{self.civ}", token=self.token)

    def submit(self, intents, reasoning=None):
        return _req("POST", f"/games/{self.game_id}/intents",
                    {"civ": self.civ, "intents": intents, "reasoning": reasoning}, token=self.token)

    def end_turn(self, delegate_rest=True):
        """Commit this round's plan. Returns CommitResult; `round` is set when this commit sealed the round."""
        return _req("POST", f"/games/{self.game_id}/commit", {"civ": self.civ, "delegateRest": delegate_rest}, token=self.token)

    def wait(self, after):
        """Long-poll until the game passes turn `after`; doubles as this seat's heartbeat."""
        return _req("GET", f"/games/{self.game_id}/wait?after={after}&civ={self.civ}", token=self.token)

    def presence(self, present):
        return _req("POST", f"/games/{self.game_id}/presence/{self.civ}?present={'true' if present else 'false'}",
                    {}, token=self.token)


def create_game(**kw):
    return _req("POST", "/games", kw, admin=True)


def autoplay(game_id, turns):
    return _req("POST", f"/games/{game_id}/autoplay?turns={turns}", {}, admin=True)


def replay(game_id):
    return _req("GET", f"/games/{game_id}/replay", admin=True)
