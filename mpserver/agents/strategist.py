"""LLM strategist seat (the Vox Deorum / CivAgent hybrid):
the agent sets research, production and war goals every few turns; Unciv's AI moves the units.

Policies:
  claude  - Claude via the Messages API with a tool for orders (needs ANTHROPIC_API_KEY)
  persona - deterministic stand-in with the same inputs/outputs, for offline demos and tests

The agent only sees what its civ sees (brief + masked view) and every order goes through the
server's IntentValidator, exactly like a human client.
"""
import argparse
import json
import os
import sys
import time
import urllib.request

sys.path.insert(0, os.path.dirname(__file__))
from client import Seat, create_game, autoplay, replay  # noqa: E402

ORDERS_TOOL = {
    "name": "submit_orders",
    "description": "Set this civ's strategy for the next few turns. Unciv's AI executes unit moves.",
    "input_schema": {
        "type": "object",
        "properties": {
            "reasoning": {"type": "string", "description": "One or two sentences on the plan."},
            "research": {"type": "string", "description": "Tech to research next (from the researchable list or a later tech)."},
            "builds": {"type": "array", "items": {"type": "object", "properties": {
                "city": {"type": "string"}, "item": {"type": "string"}}, "required": ["city", "item"]}},
            "declare_war_on": {"type": "string", "description": "Optional civ to declare war on."},
        },
        "required": ["reasoning"],
    },
}

SYSTEM = ("You are the strategist for one civilization in a turn-based 4X game (Civ V rules). "
          "You set goals; the game's own AI moves units and fills empty queues. Be decisive and brief. "
          "Only use tech, city and item names that appear in the brief.")


def claude_policy(brief, view):
    key = os.environ["ANTHROPIC_API_KEY"]
    body = {
        "model": os.environ.get("AGENT_MODEL", "claude-sonnet-5-5"),
        "max_tokens": 600,
        "system": SYSTEM,
        "tools": [ORDERS_TOOL],
        "tool_choice": {"type": "tool", "name": "submit_orders"},
        "messages": [{"role": "user", "content": brief}],
    }
    req = urllib.request.Request("https://api.anthropic.com/v1/messages", data=json.dumps(body).encode(),
                                 headers={"x-api-key": key, "anthropic-version": "2023-06-01",
                                          "content-type": "application/json"})
    with urllib.request.urlopen(req, timeout=120) as r:
        resp = json.load(r)
    orders = next(b["input"] for b in resp["content"] if b["type"] == "tool_use")
    return orders_to_intents(orders)


# Rome as a "builder that turns aggressive once ahead": a readable persona for the demo.
TECH_PLAN = ["Pottery", "Writing", "Bronze Working", "Mining", "Masonry", "Calendar", "Philosophy",
             "Mathematics", "Construction", "Iron Working", "Currency", "Civil Service", "Education"]


def persona_policy(brief, view):
    me, turn = view["me"], view["turn"]
    reasons, orders = [], {"builds": []}
    researchable = set(view["researchable"])
    target_tech = next((t for t in TECH_PLAN if t in researchable), None)
    if target_tech and me.get("researching") != target_tech:
        orders["research"] = target_tech
        reasons.append(f"Research {target_tech} next to follow the builder plan")

    settlers = me["units"].get("Settler", 0)
    military = sum(v for k, v in me["units"].items() if k not in ("Settler", "Worker", "Work Boats", "Great General"))
    for city in view["cities"]:
        opts = view["buildOptions"].get(city["name"], [])
        if city["producing"] in ("Settler",) :
            continue
        if len(view["cities"]) < 4 and city["population"] >= 2 and settlers == 0 and "Settler" in opts:
            pick, why = "Settler", f"expand: only {len(view['cities'])} cities"
            settlers += 1
        elif military < len(view["cities"]) + 1 and "Warrior" in opts or (military < 2 and "Spearman" in opts):
            pick = "Spearman" if "Spearman" in opts else "Warrior"
            why = f"defense is thin ({military} military units)"
            military += 1
        else:
            pick = next((b for b in ("Monument", "Library", "Granary", "Shrine", "Colosseum", "Temple", "Market") if b in opts), None)
            why = f"grow {city['name']}'s culture and science"
        if pick and pick != city["producing"]:
            orders["builds"].append({"city": city["name"], "item": pick})
            reasons.append(f"{city['name']}: {pick} ({why})")

    majors = [c for c in view["knownCivs"] if not c["isCityState"] and c["status"] != "War"]
    if turn >= 40 and majors and military >= 4:
        weakest = min(majors, key=lambda c: c["score"])
        if weakest["score"] < me["score"] * 0.6:
            orders["declare_war_on"] = weakest["civ"]
            reasons.append(f"{weakest['civ']} is far behind (score {weakest['score']} vs {me['score']}): strike now")

    orders["reasoning"] = "; ".join(reasons) or "Hold course: current queues match the plan"
    return orders_to_intents(orders)


def orders_to_intents(orders):
    intents = []
    if orders.get("research"):
        intents.append({"type": "research", "tech": orders["research"]})
    for b in orders.get("builds", []):
        intents.append({"type": "build", "city": b["city"], "item": b["item"]})
    if orders.get("declare_war_on"):
        intents.append({"type": "declareWar", "target": orders["declare_war_on"]})
    return intents, orders.get("reasoning")


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--turns", type=int, default=60)
    ap.add_argument("--every", type=int, default=3, help="consult the strategist every N turns")
    ap.add_argument("--offline-at", type=int, default=0, help="go OFFLINE at this turn for --offline-turns (never-wait demo)")
    ap.add_argument("--offline-turns", type=int, default=10)
    ap.add_argument("--policy", default="claude" if os.environ.get("ANTHROPIC_API_KEY") else "persona")
    ap.add_argument("--out", default="replay.json")
    args = ap.parse_args()

    policy = claude_policy if args.policy == "claude" else persona_policy
    g = create_game(mapSize="Small", majorCivs=4, cityStates=2, externalSeats=["Rome"],
                    nations=["Rome", "Greece", "Egypt", "China"], seed=7, difficulty="Prince")
    seat = Seat(g["gameId"], "Rome", g["Rome"])
    print(f"game {g['gameId']} policy={args.policy}", flush=True)

    turn = 0
    while turn < args.turns:
        if args.offline_at and turn == args.offline_at:
            print(f"turn {turn}: Rome goes OFFLINE for {args.offline_turns} turns, world keeps playing", flush=True)
            for r in autoplay(g["gameId"], args.offline_turns):
                print(f"  turn {r['turn']} (AI played Rome) ai={r['timings']['aiMs']}ms", flush=True)
            seat.presence(True)
            turn = seat.view()["turn"]
            continue
        if turn % args.every == 0:
            view = seat.view()
            brief = seat.brief()
            t0 = time.time()
            intents, reasoning = policy(brief, view)
            res = seat.submit(intents, reasoning)
            ok = sum(1 for r in res["results"] if r["accepted"])
            print(f"turn {turn}: {reasoning} [{ok}/{len(intents)} accepted, {time.time()-t0:.1f}s]", flush=True)
        r = seat.end_turn()
        turn = r["turn"]
        t = r["timings"]
        print(f"  -> turn {turn} ai={t['aiMs']}ms seat={t['seatAutomationMs']}ms mask={t['maskMs']}ms "
              f"save={t['saveMs']}ms load={t['loadMs']} size={t['saveBytes']//1024}KB", flush=True)
        if r.get("winner"):
            print("winner:", r["winner"])
            break

    data = replay(g["gameId"])
    data["policy"] = args.policy
    with open(args.out, "w") as f:
        json.dump(data, f)
    print("replay written to", args.out)


if __name__ == "__main__":
    main()
