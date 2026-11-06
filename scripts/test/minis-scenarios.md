# Scenarios to run through Minis chat on the Pixel 6 (each one is a separate chat session)

Goal of each: the Minis agent uses the android-vdisplay skill by itself (no hints about commands) and finishes
a real task on the second screen. Judge: did it finish, how many tool calls, any error it hit, any wasted detour.

1. Basic: "Open the calculator on the second screen and compute 37 x 42. Tell me the answer."
2. Browse: "On the second screen open Chrome, search for 'Android virtual display', and tell me the title of the first result."
3. Settings read: "On the second screen open Settings and tell me the Android version and the security patch level."
4. Text input (English + Chinese): "On the second screen open Notes or any text field (Chrome address bar is fine) and type 'hello 皮卡丘', then show me a screenshot description."
5. Title + takeover: "Use the second screen to open Clock, set a title that says what you are doing, then wait for me." (human takes over by long-press; check the agent reacts to 423 instead of hammering)
6. Error recovery: kill our bridge on the phone mid-task (adb shell pkill -f vdisplay.Bridge) and ask the agent to continue: it should detect it and restart, not loop.
7. Idle/cleanup: "When you are done, close the second screen." -> check no leftover processes.
8. Restart reuse: ask the agent to open the second screen twice in a row -> second should be instant (reused).

Things to record per scenario: session id, number of tool calls, wall time, final answer correctness, errors, whether it read
SKILL.md first, whether it wandered into references/ unnecessarily, whether it used `status` before acting.
