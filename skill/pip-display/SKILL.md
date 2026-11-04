---
name: pip-display
description: 在 Android 设备的 Minis 里，通过本机 adb 创建「副屏（虚拟显示器）」并以悬浮小窗显示在主屏，让 agent 在副屏里启动 App、截图、点击、滑动、输入、按键，人类可随时触摸小窗接管（自动互斥）。基于 scrcpy server（trusted 虚拟屏）+ 配套 APK。当用户提到「副屏」「虚拟屏」「画中画」「悬浮窗操作手机」「后台操作 App」「不影响主屏的自动化」「scrcpy 虚拟显示器」「agent 操作手机 App 并可接管」，或需要在 Android 本机上让 agent 操作 App 而不占用当前屏幕时使用。
version: 0.3.6
---

# pip-display：本机副屏 + 悬浮小窗 + agent 操作

同一台手机上：scrcpy server 经本机 adb(shell) 建一块虚拟屏，App 在副屏运行，画面以悬浮小窗叠在主屏；agent 用脚本操作，人触摸小窗即可接管。

## ⚠️ 出错先更新
**任何脚本报错、行为异常，第一步先更新到最新版再试一次**：重新安装最新的 pip-display 包（覆盖本目录），再运行 `start.sh`——它会自动对比设备上 APK 与本 skill 自带版本，不一致就重装。`sh scripts/agent.sh version` 可查看 skill/APK 版本是否一致（APK 的 `/version` 接口也返回 version、versionCode、protocol、sdk、device）。更新内容见 `CHANGELOG.md`。更新后仍失败，再按下面的 stage/hint 处理，并把 `version` 输出一并报告给用户。

## 最短路径（照做，别探索）
```sh
SK=/var/minis/skills/pip-display/scripts
sh $SK/start.sh                        # 一条命令：自动安装→选空闲端口→起 server/桥/小窗→等视频帧到达
sh $SK/run-app.sh com.android.chrome   # 启动并确认 App 真的在副屏前台
sh $SK/agent.sh shot /tmp/s.png        # 截图，看图，再决定下一步
sh $SK/agent.sh tap 270 480            # 副屏像素坐标
sh $SK/stop.sh
```
**所有命令都输出 JSON 或明确的 `ERR:`，退出码非 0 即失败**。失败时 JSON 里的 `stage`/`hint`/`log` 已给出原因，**直接按 `hint` 处理，不要重新读脚本或手动拼 adb 命令**。

## 前置（一次性）
Android 10+；Alpine 有 `adb curl python3`（`apk add android-tools curl python3`）；`adb devices` 能看到本机设备（无线调试，见 `references/setup.md`；配对码只在交互终端输入，不要在聊天里索取）。多设备时 `export SERIAL=<序列号>`；想自动重连 `export ADB_CONNECT=ip:port`。手机需亮屏解锁。

## start.sh 的结果
- 成功：`{"ok":true,"display":N,"port":P}`。`ok:true` 表示**虚拟屏已建且视频帧已到达**，可以直接操作。
- 失败：`{"ok":false,"stage":...,"hint":...,"log":...}`，stage 与处理：

| stage | 含义 | 处理 |
|---|---|---|
| `adb` | 设备未连接/未授权 | 按 setup.md 连接；`unauthorized` 让用户在手机上点允许 |
| `install` | 推送/安装失败 | 检查磁盘空间与 adb 授权，重试一次 |
| `port` | 27183~27282 全被占用 | 极罕见；`stop.sh` 后重试 |
| `server` | scrcpy server 没起来 | 看 `log`；jar 丢失就重跑（会自动重装） |
| `bridge` | 桥没监听 | 看 `log`；`stop.sh` 后重试 |
| `display` | 没建出虚拟屏 | 多为锁屏/桥未连上：让用户解锁亮屏后重试 |
| `frames` | 屏已建但小窗没收到画面 | 确认已授予悬浮窗权限（install.sh 会授予）、手机已解锁，重试 |

端口被占用已自动处理（顺延选空闲端口并同步给桥与小窗），不需要手动改。

## 操作（`agent.sh`）
| 命令 | 说明 |
|---|---|
| `status` | `ready`(已收到视频帧)、`frames`、`port`、`humanActive`、`error`。**以 `ready` 为准，不要只看 `connected`** |
| `shot [out.png]` | 截图（宽 540，等比），坐标系与 tap/swipe 一致；非 PNG 会报错并返回非 0 |
| `tap x y` / `swipe x1 y1 x2 y2 [ms]` | 副屏像素坐标，与 `shot` 图同尺寸（默认 540x960） |
| `key <code>` | 4=BACK 3=HOME 66=ENTER 67=DEL |
| `text "内容"` | 英文逐字注入；含中文等非 ASCII 走剪贴板粘贴（覆盖手机剪贴板） |
| `title "正在做什么"` | 设置悬浮窗顶部标题，让用户看到你在干什么（建议每个阶段更新一次，如「正在打开 X」「正在滑动浏览」） |
| `window x=.. y=.. w=<dp>` / `hide` / `show` | 移动缩放小窗；`hide` 后副屏继续运行，仍可操作和截图 |
| `version` / `resume` | 版本对比；`resume` 解除用户的「立即停止」/接管 |
| `takeover 1\|0` | 手动开关接管 |

`run-app.sh <包名>`：`ok:true` 才代表 App 已在副屏前台；`ok:false` 会带当前前台与原因（未安装/被拦截/启动慢），再用 `shot` 看画面。

## agent 工作流
`start` → `run-app` → 循环「`shot` 看图 → 一个动作 → `shot` 验证」→ `stop`。**每个动作后截图确认**；需要少截图时，连续滑动类操作可合并再截一次。首次进入 App 常有引导/权限弹窗，先截图处理。

## 悬浮窗与接管
- 画面铺满圆角卡片，标题叠在上面；Siri 式彩色流光沿边缘游走并向内铺满画面：**蓝紫橙粉＝AI 操作中（快）**，偏蓝慢＝待命，**绿＝用户在操作**，红橙＝已停止。
- AI 操作中，画面正中出现两颗等大胶囊：**「立即停止」**（红）和 **「接管」**。接管/停止后变成 **「交还 AI」**。
- **立即停止**：agent 的所有操作返回 **423 `stopped by user`**，直到用户点「交还 AI」（或你调用 `agent.sh resume`，**仅在用户明确同意后**）。收到 423 时：不要重试，告诉用户并询问下一步。
- 人手直接触摸画面也会自动接管（空闲 3 秒后交还）。
- 拖动标题区移动窗口；右下角手柄或标题处双指捏合缩放；**拖到屏幕左右边缘松手会折叠吸附，只留带箭头的小拉手，点它展开**。折叠时副屏继续运行，agent 仍可操作和截图。
- 收到 423 **不要重试轰炸**；仅在用户明确要求时才加 `force=1`。

## 已知限制（如实告知）
已验证：Pixel 4a / Android 13、Pixel 6 / Android 17（API 37）；悬浮窗视觉效果在 Pixel 6 上调校，其它机型未逐一验证。`FLAG_SECURE` 内容是否被遮挡未验证；锁屏时不可用；视频走 H.264，延迟比直通 Surface 略高（未实测）；需要 adb 连接拉起 server，尚无脱离 adb 的自启动；adb 偶发卡死（脚本已自动换端口自愈）；`text` 的中文会覆盖剪贴板。

## 目录
`scripts/`（install start stop run-app agent common device-run）、`assets/`（skilled.apk、scrcpy-server.jar 5.0.1、skilled-shell.jar）、`references/`（setup、agent-api、architecture）。scrcpy © Genymobile，Apache-2.0；源码：https://github.com/OpenMinis/scrcpy-skilled
