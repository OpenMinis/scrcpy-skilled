#!/usr/bin/env python3
# Summarise a sampler CSV: per-column median / p90 / max, and rows split by whether our stack was running.
import csv, sys, statistics as st
path = sys.argv[1]
rows = [r for r in csv.DictReader(open(path)) if r.get("ts") and r.get("cpu_used_pct_of_800") not in ("", None) and ":" in r["ts"][:3]]
def num(r, k):
    try: return float(r[k])
    except: return None
def stat(vals):
    v = sorted(x for x in vals if x is not None)
    if not v: return "-"
    return f"med {st.median(v):.0f}  p90 {v[int(len(v)*0.9)-1 if len(v)>1 else 0]:.0f}  max {v[-1]:.0f}"
print(f"{path}: {len(rows)} samples, {rows[0]['ts']} -> {rows[-1]['ts']}")
for col, label in (("cpu_used_pct_of_800", "total CPU used (% of 800, i.e. 8 cores)"),
                   ("vdisplay_app", "our APK (% of one core)"),
                   ("scrcpy_server", "scrcpy server (% of one core)"),
                   ("minis_app", "Minis app"), ("surfaceflinger", "surfaceflinger"),
                   ("system_server", "system_server"), ("mediaserver_codec", "mediaserver/codec")):
    print(f"  {label:40s} {stat([num(r, col) for r in rows])}")
def temp(col):
    v = [num(r, col) for r in rows if num(r, col) is not None]
    return f"{v[0]:.1f} -> {v[-1]:.1f}  (max {max(v):.1f})" if v else "-"
print("  temps: battery", temp("batt_c"), "| skin(disp_therm)", temp("disp_therm_c"), "| neutral", temp("neutral_therm_c"))
print("         cpu big", temp("big_c"), "| mid", temp("mid_c"), "| little", temp("little_c"))
ts = {r["thermal_status"] for r in rows if r.get("thermal_status")}
print("  thermal status values seen:", sorted(ts))
