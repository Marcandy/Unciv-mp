"""Embed a replay.json into the viewer template -> a single self-contained HTML page."""
import json, sys, os

replay, out = sys.argv[1], sys.argv[2]
here = os.path.dirname(os.path.abspath(__file__))
tpl = open(os.path.join(here, "template.html")).read()
data = json.load(open(replay))
html = tpl.replace("/*REPLAY_DATA*/null", json.dumps(data, separators=(",", ":")).replace("</", "<\\/"))
open(out, "w").write(html)
print(f"wrote {out} ({len(html)//1024} KB, {len(data['frames'])} frames)")
