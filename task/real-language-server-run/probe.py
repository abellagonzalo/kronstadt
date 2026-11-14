#!/usr/bin/env python3
"""Raw LSP probe for kotlin-lsp. Usage: probe.py <root> <plan.json> <outdir>

plan.json: {"symbols": ["Foo"], "docs": ["rel/path.kt"], "defs": [["rel.kt", line0, char0]],
            "refs": [["rel.kt", line0, char0]], "index_timeout": 1500}
"""
import json, os, subprocess, sys, threading, time, signal

root, plan_path, out = sys.argv[1], sys.argv[2], sys.argv[3]
plan = json.load(open(plan_path))
os.makedirs(out, exist_ok=True)
log = open(os.path.join(out, "messages.jsonl"), "w")
T0 = time.time()
def now(): return round(time.time() - T0, 2)
def uri(rel): return "file://" + os.path.join(root, rel)

proc = subprocess.Popen(["kotlin-lsp", "--stdio"], cwd=root, stdin=subprocess.PIPE, stdout=subprocess.PIPE,
                        stderr=open(os.path.join(out, "stderr.log"), "w"), start_new_session=False)
lock = threading.Lock(); pending = {}; nid = [0]
progress = {}  # token -> state
events = []

def send(msg):
    body = json.dumps(msg).encode()
    with lock:
        proc.stdin.write(b"Content-Length: %d\r\n\r\n" % len(body) + body); proc.stdin.flush()

def request(method, params, timeout=600):
    nid[0] += 1; i = nid[0]; ev = threading.Event(); pending[i] = [ev, None]
    t = time.time(); send({"jsonrpc": "2.0", "id": i, "method": method, "params": params})
    ok = ev.wait(timeout)
    res = pending.pop(i)[1]
    return (res if ok else {"error": "timeout"}), round(time.time() - t, 2)

def notify(method, params): send({"jsonrpc": "2.0", "method": method, "params": params})

def reader():
    f = proc.stdout
    while True:
        headers = {}
        while True:
            line = f.readline()
            if not line: return
            line = line.strip()
            if not line: break
            k, v = line.decode().split(":", 1); headers[k.lower()] = v.strip()
        msg = json.loads(f.read(int(headers["content-length"])))
        log.write(json.dumps({"t": now(), "msg": msg})[:4000] + "\n"); log.flush()
        if "id" in msg and "method" not in msg:
            if msg["id"] in pending:
                pending[msg["id"]][1] = msg; pending[msg["id"]][0].set()
        elif "id" in msg:  # server -> client request
            m = msg["method"]; events.append((now(), "request", m))
            result = None
            if m == "workspace/configuration": result = [None] * len(msg["params"]["items"])
            send({"jsonrpc": "2.0", "id": msg["id"], "result": result})
        else:
            m = msg["method"]
            if m == "$/progress":
                v = msg["params"]["value"]; tok = str(msg["params"]["token"])
                if v.get("kind") == "begin": progress[tok] = v.get("title"); events.append((now(), "progress-begin", v.get("title")))
                elif v.get("kind") == "end": events.append((now(), "progress-end", progress.pop(tok, tok)))
            elif m != "textDocument/publishDiagnostics":
                events.append((now(), m, json.dumps(msg.get("params"))[:300]))

threading.Thread(target=reader, daemon=True).start()

def tree_pids(pid):
    out_ = subprocess.run(["ps", "-A", "-o", "pid=,ppid=,rss=,pgid=,command="], capture_output=True, text=True).stdout
    rows = [l.split(None, 4) for l in out_.strip().splitlines()]
    kids = {}; info = {}
    for p, pp, rss, pg, cmd in rows: kids.setdefault(int(pp), []).append(int(p)); info[int(p)] = (int(rss), int(pg), cmd)
    res, stack = [], [pid]
    while stack:
        p = stack.pop()
        if p in info: res.append((p,) + info[p]); stack += kids.get(p, [])
    return res

peak = [0]; samples = []
def sampler():
    while proc.poll() is None:
        t = tree_pids(proc.pid); rss = sum(r[1] for r in t) // 1024
        peak[0] = max(peak[0], rss); samples.append((now(), rss)); time.sleep(2)
threading.Thread(target=sampler, daemon=True).start()

report = {"root": root}
init, dt = request("initialize", {
    "processId": os.getpid(), "clientInfo": {"name": "kronstadt-probe"}, "rootUri": "file://" + root,
    "workspaceFolders": [{"uri": "file://" + root, "name": os.path.basename(root)}],
    "capabilities": {
        "window": {"workDoneProgress": True, "showMessage": {}},
        "workspace": {"workspaceFolders": True, "configuration": True,
                      "diagnostics": {"refreshSupport": True}, "symbol": {},
                      "didChangeWatchedFiles": {"dynamicRegistration": True}},
        "textDocument": {"documentSymbol": {"hierarchicalDocumentSymbolSupport": True},
                         "diagnostic": {"dynamicRegistration": True, "relatedDocumentSupport": True},
                         "publishDiagnostics": {}, "codeAction": {"codeActionLiteralSupport": {"codeActionKind": {"valueSet": ["source.organizeImports", "quickfix", "refactor"]}}}},
    }}, timeout=1800)
report["initialize_seconds"] = dt
report["server_capabilities"] = (init.get("result") or {}).get("capabilities", init)
report["server_info"] = (init.get("result") or {}).get("serverInfo")
notify("initialized", {})
print(f"[{now()}] initialize done in {dt}s", flush=True)

# Wait for indexing: progress quiet for 20s after at least one begin/end, or timeout.
deadline = time.time() + plan.get("index_timeout", 1500)
last_change = time.time(); last_n = 0
while time.time() < deadline:
    time.sleep(2)
    if plan.get("poll") and int(now()) % 10 < 2:
        r, dt = request("workspace/symbol", {"query": plan["poll"]}, timeout=60)
        report.setdefault("poll", []).append([now(), dt, len(r.get("result") or []), r.get("error")])
    if len(events) != last_n: last_n = len(events); last_change = time.time()
    if not progress and time.time() - last_change > 30: break
report["index_ready_at"] = now()
report["open_progress_at_ready"] = dict(progress)
print(f"[{now()}] indexing quiet; open progress={progress}", flush=True)

q = {}
for s in plan.get("symbols", []):
    r, dt = request("workspace/symbol", {"query": s})
    res = r.get("result") or []
    q[f"workspace/symbol {s}"] = {"seconds": dt, "count": len(res), "first": res[:3], "error": r.get("error")}
for d in plan.get("docs", []):
    text = open(os.path.join(root, d)).read()
    notify("textDocument/didOpen", {"textDocument": {"uri": uri(d), "languageId": "kotlin", "version": 1, "text": text}})
    r, dt = request("textDocument/documentSymbol", {"textDocument": {"uri": uri(d)}})
    q[f"documentSymbol {d}"] = {"seconds": dt, "result": r.get("result"), "error": r.get("error")}
    r, dt = request("textDocument/diagnostic", {"textDocument": {"uri": uri(d)}})
    q[f"diagnostic {d}"] = {"seconds": dt, "result": r.get("result"), "error": r.get("error")}
for kind, method in (("defs", "textDocument/definition"), ("refs", "textDocument/references")):
    for d, line, ch in plan.get(kind, []):
        text = open(os.path.join(root, d)).read()
        notify("textDocument/didOpen", {"textDocument": {"uri": uri(d), "languageId": "kotlin", "version": 1, "text": text}})
        params = {"textDocument": {"uri": uri(d)}, "position": {"line": line, "character": ch}}
        if kind == "refs": params["context"] = {"includeDeclaration": False}
        r, dt = request(method, params)
        q[f"{method} {d}:{line}:{ch}"] = {"seconds": dt, "result": r.get("result"), "error": r.get("error")}
report["queries"] = q

tree = tree_pids(proc.pid)
report["process_tree"] = [{"pid": p, "rss_mb": rss // 1024, "pgid": pg, "cmd": cmd[:200]} for p, rss, pg, cmd in tree]
report["rss_now_mb"] = sum(r[1] for r in tree) // 1024
report["rss_peak_mb"] = peak[0]

mode = plan.get("exit_mode", "shutdown")
pids = [p for p, *_ in tree]
if mode == "shutdown":
    r, dt = request("shutdown", None, timeout=30); notify("exit", None)
    report["shutdown"] = {"seconds": dt, "error": r.get("error")}
else:  # simulate Kronstadt dying: kill only the launcher
    os.kill(proc.pid, signal.SIGKILL)
time.sleep(10)
alive = subprocess.run(["ps", "-o", "pid=,command=", "-p", ",".join(map(str, pids))], capture_output=True, text=True).stdout
report["exit_mode"] = mode
report["alive_10s_after_exit"] = alive.strip().splitlines()
report["events"] = events
report["rss_samples"] = samples[::5]
json.dump(report, open(os.path.join(out, "report.json"), "w"), indent=1)
print(f"[{now()}] done; peak rss {peak[0]} MB; alive after exit: {alive.strip()}", flush=True)
os._exit(0)
