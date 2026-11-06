---
name: android-vdisplay
description: 在 Android 设备的 Minis 里，通过本机 adb 创建「副屏（虚拟显示器）」并以悬浮小窗显示在主屏，让 agent 在副屏里启动 App、截图、点击、滑动、输入、按键，人类可随时触摸小窗接管（自动互斥）。基于 scrcpy server（trusted 虚拟屏）+ 配套 APK。当用户提到「副屏」「虚拟屏」「画中画」「悬浮窗操作手机」「后台操作 App」「不影响主屏的自动化」「scrcpy 虚拟显示器」「agent 操作手机 App 并可接管」，或需要在 Android 本机上让 agent 操作 App 而不占用当前屏幕时使用。
version: 0.11.0
---

# android-vdisplay：本机副屏 + 悬浮小窗 + agent 操作

同一台手机上：scrcpy server 经本机 adb(shell) 建一块虚拟屏，App 在副屏运行，画面以悬浮小窗叠在主屏；agent 用脚本操作，人触摸小窗即可接管。

## ⚠️ 出错先更新
**任何脚本报错、行为异常，第一步先更新到最新版再试**：重新安装最新的 android-vdisplay 包，运行 `start.sh` 会自动检测并更新设备端 APK。`sh scripts/agent.sh version` 可查看版本一致性。更新后仍失败，按 JSON 里的 `stage`/`hint` 处置。

## 最短路径（照做，别探索）
```sh
SK=/var/minis/skills/android-vdisplay/scripts
sh $SK/start.sh                        # 自动安装→选空闲端口→起 server/桥/小窗→等视频帧到达
sh $SK/run-app.sh com.android.chrome   # 启动并确认 App 真的在副屏前台
sh $SK/agent.sh shot /tmp/s.jpg jpg 70 # 截图看图（JPEG 比 PNG 快 2 倍、省 3/4 体积与 token）
sh $SK/agent.sh tap 270 480            # 视觉识别后点击（参考坐标系 540x960）
sh $SK/stop.sh                         # 结束并退出
```
所有命令输出 JSON 或明确的 `ERR:`，退出码非 0 即失败。直接按 JSON 中的 `hint` 处理，不要手动拼 adb 命令。

## 前置（一次性）
Android 10+；Alpine 需 `adb curl python3`（`apk add android-tools curl python3`）；`adb devices` 能看到本机设备（无线调试，见 `references/setup.md`）。多设备时 `export SERIAL=<序列号>`；想自动重连 `export ADB_CONNECT=ip:port`。手机需亮屏解锁。

## start.sh 状态码
- 成功：`{"ok":true,"display":N,"port":P,"reused":false}`。`ok:true` 表示**虚拟屏已建且画面已真正渲染**（`rendered>0`），可立即操作。
- **复用**：若本 scid 的副屏仍健康（APK/server 存活、`ready:true`、未关闭、尺寸一致），`start.sh` 直接返回同一块屏（`"reused":true`，约 1 s，不动任何东西）。要强制重建：`start.sh --fresh` 或 `VDISPLAY_FRESH=1`。display ID 在一次开机内只增不减，这是 Android 正常行为，不是泄漏。
- 首次 3 秒内没渲染出画面时，`start.sh` 会先保存日志再自动重建一次（`"healed":true`）；仍失败则 `stage:"render"`。日志存于 `~/.android-vdisplay/logs/<时间戳>/`（保留最近 5 份，`stop.sh` 也会保存），JSON 里的 `savedLogs` 给出路径。
- 失败：返回 `stage`/`hint`/`log`。常用 stage：
  - `adb`：设备未连/未授权（按 setup.md 操作）
  - `server`/`bridge`：看 log；重试一次即可
  - `display`：多为手机锁屏或桥未连，解锁亮屏后重试
  - `frames`：小窗未收到视频包，确认悬浮窗权限后重试
  - `render`：有视频包但解码器没渲染出画面（截图会是纯黑）；已自动重建一次仍失败，看 `savedLogs`

## 核心操作（`agent.sh`）
| 命令 | 说明 |
|---|---|
| `status` | `ready:true` = 解码器**已渲染出画面**（`rendered>0`）；`frames` 只是收到的包数；另有 `dropped`、`decodeErrors`、`port`、`humanActive`、`error` |
| `shot [path] [png\|jpg\|webp] [q]` | 截图（默认 540 宽）；看图推荐 `jpg 70` 省 token |
| `tap x y` / `swipe x1 y1 x2 y2 [ms]` | 像素坐标，与 `shot` 尺寸完全一致（默认 540 宽等比） |
| `key <code>` | 4=BACK 3=HOME 66=ENTER 67=DEL |
| `text "内容"` | 英文逐字输入；中文等非 ASCII 自动走剪贴板粘贴 |
| `title "文案"` | 动态更新小窗顶栏标题（让用户看到正在干什么） |
| `window x=.. y=.. w=<dp>` / `hide` / `show` | 调窗；`hide` 移出主屏但副屏照跑、仍可截图操作 |
| `version` / `resume` | 版本核对；`resume` 解除用户的接管态 |

`run-app.sh <包名>`：只有 `ok:true` 才代表 App 已在副屏前台；若失败会直接报原因（未安装 / 未到前台）。

## ⚠️ 核心工作流与铁律
1. **工作流**：`start` → `run-app` → 循环「`shot` 看图 → 一个动作 → `shot` 验证」→ `stop`。每个动作后必须截图确认。
2. **严禁尝试读取副屏控件树**：
   - `android-a11y-cli ui dump` 与 `uiautomator dump` **均读不到副屏**，底层只会返回主屏的 45 个节点（SystemUI / Minis / 悬浮窗）。
   - **绝对不要**尝试 `--display`、`--package`、`find --text` 搜副屏，详见 [references/accessibility.md](references/accessibility.md)。
   - **唯一正解**：`agent.sh shot ... jpg 70` 截图，由视觉模型定位坐标后 `tap`。
3. **人类接管与 423**：
   - 用户在小窗定点长按 1 秒或点右上角停止按钮进入接管，此时 agent 控制接口返回 **HTTP 423**（`human has control` / `stopped by user`）。
   - 收到 423 时**绝不可重试轰炸**，暂停并告知用户，等用户点「交还 AI」后再继续。详见 [references/takeover.md](references/takeover.md)。
4. **闲置 10 分钟自动关闭**：连续 10 分钟既没有任何 agent 请求（包括 `status` 轮询），也没有人碰小窗，副屏会自行退出（最后一分钟标题显示倒计时）。退出后 `/status` 不再可达；如需继续，重新 `start.sh`。想改时长：`IDLE_MIN=30 sh $SK/start.sh`，`IDLE_MIN=0` 表示不自动关闭。用完仍应主动 `stop.sh`。

5. **黑屏分诊**：截图纯黑（JPEG 体积每次一样且很小）或主屏小窗显示 "AI standby" 却无画面时，先截**主屏**确认，然后直接 `stop.sh && start.sh`，**不要反复重试截图**。0.11.0 起 `start.sh` 已自动检测并处理这种情况。

## 渐进式参考文档
- [references/setup.md](references/setup.md)：本机无线调试配对与连通性指引
- [references/takeover.md](references/takeover.md)：手势（长按接管、双指缩放、折叠拉手）与 423 详细规则
- [references/accessibility.md](references/accessibility.md)：为什么副屏无法读控件树的技术原理与实测证据
- [references/agent-api.md](references/agent-api.md)：APK 内置 HTTP 接口（127.0.0.1:28080）完整协议与返回结构
- [references/architecture.md](references/architecture.md)：scrcpy-server + Bridge + APK 跨进程架构

## 已知限制
已验证：Pixel 4a (Android 13)、Pixel 6 (Android 17)；副屏控件树不可读（必须视觉定位）；锁屏时不可用；视频走 H.264 本机 loopback；中文输入会覆盖手机系统剪贴板。

## 目录
`scripts/`（start / stop / run-app / agent / install / common / device-run）、`assets/`（vdisplay.apk、scrcpy-server.jar 5.0.1、vdisplay-shell.jar）、`references/`。源码：https://github.com/OpenMinis/scrcpy-skilled
