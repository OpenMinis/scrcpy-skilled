# scrcpy-skilled

Give an AI agent a **second screen on the same Android phone**. It creates a virtual display on the device, shows it in a floating Picture-in-Picture window on the main screen, lets the agent launch apps, read the widget tree, take screenshots and inject touch / text / keys — and lets the human grab control at any time.

Built on [Genymobile/scrcpy](https://github.com/Genymobile/scrcpy) (git submodule at `third_party/scrcpy`, unmodified, v5.0.1).

## This repository is the source of the `android-vdisplay` skill

The finished, distributable skill lives in **[OpenMinis/MinisSkills → `android-vdisplay`](https://github.com/OpenMinis/MinisSkills/tree/main/android-vdisplay)**. This repo holds the source (APK, shell bridge, build scripts, tests, experiment notes) that the skill is built from. **You do not need to build anything here to use it.**

## Install the skill

Pick either way:

1. **Give the skill URL to your agent** (for example Minis). Copy this link and tell the agent to install it:
   `https://github.com/OpenMinis/MinisSkills/tree/main/android-vdisplay`
2. **Browse and import from the command line.** Browse the skills in the MinisSkills repo, select `android-vdisplay`, and import it.

Then follow the skill's `SKILL.md`: pair Wireless Debugging once (see `references/setup.md` in the skill), and the agent runs `start.sh` → `run-app.sh` → `agent.sh ui` / `tap` / `shot` → `stop.sh`.

Requirements: Android 10+, Wireless Debugging enabled, `adb` available where the agent runs.

## How it works

```
 scrcpy-server (adb shell) ──► trusted virtual display + H.264 encoder
        │ abstract socket
 Bridge (adb shell) ─────────► 127.0.0.1:<port>   (SELinux blocks apps from the abstract socket)
        │ TCP
 APK (overlay + accessibility) ► decodes video into a rounded floating card, takeover gestures,
                                 reads the virtual display's widget tree, serves 127.0.0.1:28080
```

See `skill/android-vdisplay/references/architecture.md` for details.

## Layout

| Path | Description |
|---|---|
| `app/` | Overlay APK (`app.openminis.vdisplay`): floating window, decoder, agent HTTP API, accessibility service |
| `shell/` | Shell-identity TCP bridge |
| `skill/android-vdisplay/` | The skill as shipped (scripts, docs, references) |
| `scripts/` | Build scripts (`build-apk.sh`, `build-scrcpy-server.sh`), `remote.sh`, and `scripts/test/*` |
| `third_party/scrcpy` | scrcpy submodule (read-only dependency) |
| `docs/`, `experiments/` | Design notes and the original proofs of concept |

## Build from source

```sh
git clone --recurse-submodules https://github.com/OpenMinis/scrcpy-skilled.git
scripts/build-scrcpy-server.sh   # builds scrcpy-server.jar from the submodule (no source changes)
scripts/build-apk.sh             # builds skilled.apk and skilled-shell.jar
```

`*.apk` is git-ignored, so `skilled.apk` is not tracked here; the released skill in MinisSkills includes it. `scripts/remote.sh` and `scripts/test/*` default to the maintainers' own test hosts — override `REMOTE` / `SERIAL` for your setup.

## License

MIT, see `LICENSE`. scrcpy is Apache-2.0 (Genymobile).
