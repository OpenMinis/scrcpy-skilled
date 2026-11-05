#!/usr/bin/env python3
"""pip-display performance benchmark (iSH side): ships perf-remote.sh to the Mac, runs it there in ONE ssh
session (so the iSH->ssh hop is not part of any sample), and aggregates the numbers.

Usage: perf.py [N=15] [--out FILE]
Stages measured on the Mac next to the phone: cold start (per-stage, from start.sh's own timing), http floor,
tap/swipe/key/text, screenshots (3 sizes + the main-screen screencap for comparison), uiautomator dump, run-app.
"""
import json, os, statistics, subprocess, sys, time

ROOT = os.path.dirname(os.path.dirname(os.path.dirname(os.path.abspath(__file__))))
REMOTE = os.path.join(ROOT, "scripts", "remote.sh")
N = int(next((a for a in sys.argv[1:] if a.isdigit()), 15))
OUT = sys.argv[sys.argv.index("--out") + 1] if "--out" in sys.argv else os.path.join(ROOT, "docs", "perf-latest.json")
COLD = 5


def remote(*args, inp=None, timeout=900):
    p = subprocess.run(["sh", REMOTE, *args], capture_output=True, text=True, timeout=timeout, input=inp)
    return p.stdout


def st(xs):
    xs = sorted(xs); q = lambda p: xs[min(len(xs) - 1, int(round(p * (len(xs) - 1))))]
    return dict(n=len(xs), min=xs[0], median=statistics.median(xs), p90=q(.9), max=xs[-1])


# ---- 1. cold start: use the per-stage "ms" that start.sh itself reports ----
cold = {}
for i in range(COLD):
    remote("run", "stop.sh", "quiet"); time.sleep(2)
    o = remote("run", "start.sh", timeout=200)
    try:
        j = json.loads(o.strip().splitlines()[0])
        for k, v in j["ms"].items():
            cold.setdefault(k, []).append(v)
    except Exception:
        print("cold start", i, "failed:", o[:160])
remote("run", "run-app.sh", "com.google.android.calculator")
disp = open(os.path.expanduser("~/.pip-display/display")).read().strip() if os.path.exists(os.path.expanduser("~/.pip-display/display")) else ""

# ---- 2. everything else, measured on the Mac ----
script = open(os.path.join(ROOT, "scripts", "test", "perf-remote.sh")).read()
subprocess.run(["sh", REMOTE, "sh", "cat > /tmp/perf-remote.sh"], input=script, text=True, capture_output=True)
did = remote("sh", "grep -o 'New display: .*id=[0-9]*' /dev/null; adb -s $SERIAL shell \"grep -o 'New display: .*id=[0-9]*' /data/local/tmp/sc/server.log | tail -1 | sed 's/.*id=//'\"").strip()
out = remote("sh", f"N={N} DISP={did} SK=/tmp/pip-display bash /tmp/perf-remote.sh")
remote("run", "agent.sh", "resume")

samples, sizes, timing, nodes = {}, {}, [], {}
for line in out.splitlines():
    p = line.split("\t")
    if len(p) == 2 and p[0] != "TIMING":
        try: samples.setdefault(p[0], []).append(float(p[1]))
        except ValueError: pass
    elif p[0] == "SIZE": sizes[p[1]] = int(p[2])
    elif p[0] == "NODES": nodes[p[1]] = int(p[2])
    elif p[0] == "TIMING": timing.append(dict(kv.split("=") for kv in p[1].split(",")))

rows = {f"start: {k}": st(v) for k, v in cold.items()}
rows.update({k: st(v) for k, v in samples.items() if v})
print(f"\npip-display perf — Pixel 6 / Android 17, N={N} (cold start N={COLD}), device driven from a Mac on the same LAN\n")
print(f"{'stage':50s} {'n':>3s} {'min':>7s} {'median':>7s} {'p90':>7s} {'max':>7s}   (ms)")
for k, s in rows.items():
    print(f"{k:50s} {s['n']:3d} {s['min']:7.0f} {s['median']:7.0f} {s['p90']:7.0f} {s['max']:7.0f}")
if timing:
    avg = lambda key: statistics.fmean(float(t[key]) for t in timing if key in t)
    print(f"\nscreenshot, server-side (avg of {len(timing)}): capture={avg('capture'):.0f} ms  scale={avg('scale'):.0f} ms  encode={avg('encode'):.0f} ms  size={timing[0].get('size')}")
print("sizes (bytes):", sizes)
print("ui tree nodes:", nodes)
os.makedirs(os.path.dirname(OUT), exist_ok=True)
json.dump(dict(n=N, cold_n=COLD, when=time.strftime("%Y-%m-%d %H:%M:%S"), device="Pixel 6 / Android 17", stages=rows,
               sizes=sizes, ui_nodes=nodes, shot_server_ms=timing), open(OUT, "w"), indent=1)
print("saved", OUT)
