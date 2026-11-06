#!/usr/bin/env python3
# Compare the five perf phases side by side. usage: compare-phases.py <prefix>   (reads <prefix>-<phase>.csv)
import csv, sys, statistics as st
pre = sys.argv[1]
phases = ["idle", "running", "active", "video", "closed"]
cols = [("cpu_used_of_800", "CPU total (of 800)"), ("vdisplay_app", "our APK"), ("scrcpy_server", "scrcpy server"),
        ("minis_app", "Minis app"), ("surfaceflinger", "surfaceflinger"), ("system_server", "system_server"),
        ("codec", "codec"), ("batt_c", "battery C"), ("skin_c", "skin C"), ("cpu_max_c", "hottest CPU C"), ("gpu_c", "GPU C")]
data = {}
for ph in phases:
    rows = []
    for r in csv.DictReader(open(f"{pre}-{ph}.csv")):
        if r.get("ts") and r["ts"][:1].isdigit() and r.get("cpu_used_of_800", "") != "":
            rows.append(r)
    data[ph] = rows
def val(rows, k, fn):
    v = []
    for r in rows:
        try: v.append(float(r[k]))
        except: pass
    return fn(v) if v else None
def fmt(x, d=0): return "  -  " if x is None else f"{x:.{d}f}"
print("MEDIAN per phase (CPU columns are % of ONE core per process; total is out of 800)")
print(f"{'':20s}" + "".join(f"{p:>10s}" for p in phases))
print(f"{'samples':20s}" + "".join(f"{len(data[p]):>10d}" for p in phases))
for k, label in cols:
    d = 1 if k.endswith("_c") else 0
    print(f"{label:20s}" + "".join(f"{fmt(val(data[p], k, st.median), d):>10s}" for p in phases))
print("\nMAX per phase")
for k, label in cols[:7] + cols[9:10]:
    d = 1 if k.endswith("_c") else 0
    print(f"{label:20s}" + "".join(f"{fmt(val(data[p], k, max), d):>10s}" for p in phases))
print("\ntemperature change from first to last sample of each phase (C)")
for k, label in cols[7:]:
    out = []
    for p in phases:
        v = [float(r[k]) for r in data[p] if r.get(k, "") != ""]
        out.append("  -  " if len(v) < 2 else f"{v[-1]-v[0]:+.1f}")
    print(f"{label:20s}" + "".join(f"{x:>10s}" for x in out))
