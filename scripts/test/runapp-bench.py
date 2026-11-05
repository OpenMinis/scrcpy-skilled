#!/usr/bin/env python3
"""run-app.sh latency + failure paths. Runs on the Mac next to the phone (SERIAL env)."""
import os, subprocess, time

env = dict(os.environ, ADB_CONNECT=os.environ.get("SERIAL", "10.1.10.11:5555"),
           PATH="/opt/homebrew/bin:" + os.environ["PATH"])
env.setdefault("SERIAL", "10.1.10.11:5555")
SK = os.environ.get("SK", "/tmp/android-vdisplay/scripts")
CALC = "com.google.android.calculator"


def run(cmd):
    a = time.perf_counter()
    p = subprocess.run(cmd, shell=True, env=env, capture_output=True, text=True, cwd=SK)
    return (time.perf_counter() - a) * 1000, p.stdout.strip()


good = '"ok":true'
xs, bad = [], 0
for i in range(6):
    run("sh run-app.sh com.android.settings")          # switch away first so the next launch is a real switch
    ms, o = run(f"sh run-app.sh {CALC}")
    if good in o:
        xs.append(ms)
    else:
        bad += 1
        print("   FAIL:", o[:160])
xs.sort()
if xs:
    print(f"run-app (switch)    n={len(xs)} failures={bad}  median {xs[len(xs)//2]:.0f} ms  min {xs[0]:.0f}  max {xs[-1]:.0f}")
ms, o = run("sh run-app.sh com.example.notinstalled")
print(f"not installed       {ms:.0f} ms   {o[:100]}")
ms, o = run(f"sh run-app.sh {CALC}")
print(f"already foreground  {ms:.0f} ms   ok={good in o}")
