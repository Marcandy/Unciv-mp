"""Tiny client for the mpserver HTTP API (stdlib only)."""
import json
import os
import urllib.request

BASE = os.environ.get("GAME_URL", "http://127.0.0.1:8090")


def _req(method, path, body=None, token=None, raw=False):
    data = json.dumps(body).encode() if body is not None else None
    req = urllib.request.Request(BASE + path, data=data, method=method)
    req.add_header("Content-Type", "application/json")
    if token:
        req.add_header("X-Seat-Token", token)
    # Local server: bypass any configured HTTPS proxy.
    opener = urllib.request.build_opener(urllib.request.ProxyHandler({}))
    with opener.open(req, timeout=600) as r:
        payload = r.read().decode()
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

    def end_turn(self):
        return _req("POST", f"/games/{self.game_id}/end-turn", {"civ": self.civ}, token=self.token)

    def presence(self, present):
        return _req("POST", f"/games/{self.game_id}/presence/{self.civ}?present={'true' if present else 'false'}",
                    {}, token=self.token)


def create_game(**kw):
    return _req("POST", "/games", kw)


def autoplay(game_id, turns):
    return _req("POST", f"/games/{game_id}/autoplay?turns={turns}", {})


def replay(game_id):
    return _req("GET", f"/games/{game_id}/replay")
