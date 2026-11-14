#!/usr/bin/env python3
"""Drive Kronstadt over MCP stdio. Usage: mcp_probe.py <kronstadt-bin> <root> <calls.json> <out.json>
calls.json: [{"at": seconds_after_start, "tool": name, "args": {...}}, ...]"""
import json, subprocess, sys, time, threading
bin_, root, calls_path, out = sys.argv[1:5]
calls = json.load(open(calls_path))
T0 = time.time()
p = subprocess.Popen([bin_, "--project-root", root, "--language-server", "kotlin-lsp --stdio"], stdin=subprocess.PIPE,
                     stdout=subprocess.PIPE, stderr=open(out + ".stderr", "w"), text=True, bufsize=1)
nid = [0]
def rpc(method, params=None, notify=False):
    msg = {"jsonrpc": "2.0", "method": method}
    if params is not None: msg["params"] = params
    if not notify: nid[0] += 1; msg["id"] = nid[0]
    p.stdin.write(json.dumps(msg) + "\n"); p.stdin.flush()
    if notify: return None
    while True:
        line = p.stdout.readline()
        if not line: raise SystemExit("kronstadt closed stdout")
        r = json.loads(line)
        if r.get("id") == nid[0]: return r
results = []
t = time.time(); init = rpc("initialize", {"protocolVersion": "2025-06-18", "capabilities": {}, "clientInfo": {"name": "probe", "version": "0"}})
results.append({"step": "initialize", "seconds": round(time.time() - t, 2), "instructions": init.get("result", {}).get("instructions")})
rpc("notifications/initialized", notify=True)
for c in calls:
    wait = c["at"] - (time.time() - T0)
    if wait > 0: time.sleep(wait)
    t = time.time()
    r = rpc("tools/call", {"name": c["tool"], "arguments": c["args"]})
    res = r.get("result") or r.get("error")
    text = "\n".join(x.get("text", "") for x in (res.get("content") or [])) if isinstance(res, dict) else str(res)
    results.append({"at": round(t - T0, 1), "tool": c["tool"], "args": c["args"], "seconds": round(time.time() - t, 2),
                    "isError": isinstance(res, dict) and res.get("isError"), "text": text})
    print(f"[{round(t - T0, 1)}] {c['tool']} {c['args']} -> {round(time.time() - t, 2)}s, {len(text)} chars", flush=True)
p.stdin.close()
t = time.time(); code = p.wait(60)
results.append({"step": "exit", "seconds": round(time.time() - t, 2), "code": code})
json.dump(results, open(out, "w"), indent=1)
