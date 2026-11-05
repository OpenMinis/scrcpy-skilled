#!/usr/bin/env python3
"""Send a prompt to the Minis app on the Android phone through its debug server (5321) and print what happened.

Runs on the Mac (mini2): reaches the phone's loopback debug server through `adb forward` (no token needed on loopback).
usage: minis-ask.py "<prompt>" [--wait 240] [--session <id>]
Prints the assistant's tool calls (exact command + output) and the final answer, so you can see what Minis really did.
"""
import argparse, json, os, subprocess, sys, time, urllib.request

SERIAL = os.environ.get("SERIAL", "10.1.10.11:5555")
LP = int(os.environ.get("LP", "25321"))
ADB = "/opt/homebrew/bin/adb"


def rpc(method, params=None, timeout=60):
    body = json.dumps({"jsonrpc": "2.0", "id": 1, "method": method, "params": params or {}}).encode()
    req = urllib.request.Request(f"http://127.0.0.1:{LP}/", data=body, headers={"Content-Type": "application/json"})
    with urllib.request.urlopen(req, timeout=timeout) as r:
        d = json.loads(r.read())
    if "error" in d:
        raise RuntimeError(f"{method}: {d['error']}")
    return d["result"]


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("prompt")
    ap.add_argument("--wait", type=int, default=240)
    ap.add_argument("--session")
    a = ap.parse_args()
    subprocess.run([ADB, "-s", SERIAL, "forward", f"tcp:{LP}", "tcp:5321"], capture_output=True)

    p = {"prompt": a.prompt}
    if a.session:
        p["sessionId"] = a.session
    r = rpc("chat.prompt", p, timeout=30)             # async: returns the session id immediately
    sid = r.get("sessionId") or r.get("session_id")
    print("session:", sid)

    t0 = time.time()
    time.sleep(4)                                      # status can still read "not running" for a moment right after submit
    idle = 0
    while time.time() - t0 < a.wait:
        st = rpc("chat.session.status", {"sessionId": sid})
        idle = 0 if st.get("isRunning") else idle + 1
        if idle >= 2:                                  # two consecutive "not running" polls = really finished
            break
        time.sleep(3)
    else:
        print("!! still running after", a.wait, "s — cancelling")
        rpc("chat.session.cancel", {"sessionId": sid})

    msgs = rpc("chat.messages.list", {"sessionId": sid, "includeTools": True, "limit": 200})
    for m in msgs.get("messages", []):
        role = m.get("role")
        for part in m.get("parts", []):
            v = part.get("value")
            if part.get("type") == "text" and role == "user":
                print(f"\n[user] {str(v)[:300]}")
            elif part.get("type") == "text":
                print(f"\n[assistant] {str(v)[:2500]}")
            elif part.get("type") == "toolUse":
                try:
                    cmd = json.loads(v["input"]).get("command", v["input"])
                except Exception:
                    cmd = v.get("input")
                print(f"\n  [tool call] {str(cmd)[:900]}")
            elif part.get("type") == "toolResult":
                print(f"  [tool result] {str(v.get('output'))[:int(os.environ.get('MAXOUT', '2500'))]}")
    print("\n(elapsed %.0fs)" % (time.time() - t0))


if __name__ == "__main__":
    main()
