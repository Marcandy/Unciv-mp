"""MCP server exposing one game seat, so any MCP client (Claude Code, Claude Desktop, other agents)
can play a civ through the same validated intent API as a human client.

Run:  GAME_ID=... SEAT=Rome SEAT_TOKEN=... python3 mcp_server.py
Claude Code:  claude mcp add unciv-seat -e GAME_ID=... -e SEAT=Rome -e SEAT_TOKEN=... -- python3 /path/to/mcp_server.py
"""
import os
import sys

sys.path.insert(0, os.path.dirname(__file__))
from client import Seat  # noqa: E402
from mcp.server import MCPServer  # noqa: E402

seat = Seat(os.environ["GAME_ID"], os.environ.get("SEAT", "Rome"), os.environ["SEAT_TOKEN"])

server = MCPServer(
    name="unciv-seat",
    instructions=(
        "You play one civilization. Each turn: call get_brief, decide, call submit_intents, then end_turn. "
        "You set goals (research, production, war); the game's AI moves your units. "
        "Only use names that appear in the brief."
    ),
)


@server.tool(description="Narrated summary of what your civ knows this turn.")
def get_brief() -> str:
    return seat.brief()


@server.tool(description="Structured masked view: your cities, known civs, explored tiles, build options.")
def get_view() -> dict:
    return seat.view()


@server.tool(description="Submit intents. Each is {type: research|build|declareWar|note, tech?, city?, item?, target?, text?}. "
                         "Returns which were accepted and why.")
def submit_intents(intents: list[dict], reasoning: str = "") -> dict:
    return seat.submit(intents, reasoning)


@server.tool(description="End your turn. The server resolves AI civs and returns timings and new events.")
def end_turn() -> dict:
    return seat.end_turn()


if __name__ == "__main__":
    server.run("stdio")
