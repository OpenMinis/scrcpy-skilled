#!/bin/bash
# Screenshot format comparison on the Mac: latency (client-side) + bytes + server-side encode time, N runs each.
export PATH=/opt/homebrew/bin:$PATH
N=${N:-10}; FP=$(cat ~/.android-vdisplay/fport 2>/dev/null || echo 28090); H=http://127.0.0.1:$FP
python3 - "$H" "$N" <<'EOF'
import sys, time, urllib.request, statistics
H, N = sys.argv[1], int(sys.argv[2])
def get(q):
    t = time.perf_counter(); r = urllib.request.urlopen(f"{H}/screenshot?{q}", timeout=15); b = r.read()
    return (time.perf_counter() - t) * 1000, len(b), r.headers.get("X-Timing-Ms", ""), r.headers.get("Content-Type")
rows = [("png  w=540 (default)", "w=540"), ("png  full 1080x2352", ""),
        ("jpg  w=540 q=80", "w=540&fmt=jpg&q=80"), ("jpg  w=540 q=60", "w=540&fmt=jpg&q=60"),
        ("jpg  full  q=80", "fmt=jpg&q=80"), ("webp w=540 q=80", "w=540&fmt=webp&q=80")]
print(f"{'format':24s} {'median ms':>9s} {'min':>6s} {'bytes':>9s}  server-side (capture/scale/encode)")
for name, q in rows:
    xs = []; info = None
    for _ in range(N):
        ms, n, tm, ct = get(q); xs.append(ms); info = (n, tm, ct)
    xs.sort(); print(f"{name:24s} {xs[len(xs)//2]:9.0f} {xs[0]:6.0f} {info[0]:9d}  {info[1]}  [{info[2]}]")
EOF
