---
name: pip-display
description: 在 Android 设备的 Minis 里，通过本机 adb 创建「副屏（虚拟显示器）」并以悬浮小窗显示在主屏，让 agent 在副屏里启动 App、截图、点击、滑动、输入、按键，人类可随时触摸小窗接管（自动互斥）。基于 scrcpy server（trusted 虚拟屏）+ 配套 APK。当用户提到「副屏」「虚拟屏」「画中画」「悬浮窗操作手机」「后台操作 App」「不影响主屏的自动化」「scrcpy 虚拟显示器」「agent 操作手机 App 并可接管」，或需要在 Android 本机上让 agent 操作 App 而不占用当前屏幕时使用。
version: 0.1.0
---

# pip-display：本机副屏 + 悬浮小窗 + agent 操作

在**同一台 Android 手机**上：scrcpy server 通过本机 adb(shell 权限) 建一块虚拟屏，App 在这块副屏里运行，画面以悬浮小窗叠在主屏；agent 用 HTTP/脚本操作副屏，人类触摸小窗即可接管。

```
agent ─ scripts/agent.sh ─ adb forward ─► APK(悬浮窗+HTTP:28080) ─► scrcpy 控制通道 ─► 副屏
副屏画面 ◄─ H.264 ◄─ scrcpy server(shell) ◄─ TCP 桥(shell) ◄─ APK 解码到小窗
```

## 前置条件（先检查，缺一不可）
1. **Android 10+（API 29+）**。已在 Pixel 4a / Android 13 实测，其它机型未验证。
2. Minis 的 Alpine 里有 `adb`、`curl`、`python3`（`apk add android-tools curl python3`）。
3. **adb 能连到本机设备**：开发者选项 → 无线调试，配对并 `adb connect`。步骤见 `references/setup.md`。配对码必须由用户在交互终端输入，不要在聊天里索取。
4. 手机**亮屏且已解锁**（锁屏时小窗不可见、截图失败）。

## 快速使用
```sh
SK=/var/minis/skills/pip-display/scripts
export SERIAL=<adb devices 里的序列号>      # 只有一个设备可省略
sh $SK/install.sh                            # 首次：推送 jar、装 APK、授予悬浮窗权限
sh $SK/start.sh                              # 起副屏 + 小窗，输出 DISPLAY_ID=N
sh $SK/run-app.sh com.android.chrome         # 在副屏启动 App
sh $SK/agent.sh shot /tmp/s.png              # 截图（副屏画面，540x960）
sh $SK/agent.sh tap 270 480                  # 点击（副屏像素坐标）
sh $SK/stop.sh                               # 关闭副屏（其中的 App 回落主屏）
```
`start.sh [宽x高/dpi] [小窗x] [小窗y] [小窗宽dp]`，默认 `540x960/240 20 300 200`。

## 操作命令（`agent.sh`）
| 命令 | 说明 |
|---|---|
| `status` | 连接状态、副屏尺寸、`humanActive` |
| `shot [out.png]` | 副屏截图（PixelCopy，小窗隐藏时也可用） |
| `tap x y` / `swipe x1 y1 x2 y2 [ms]` | 点击/滑动，坐标是副屏像素 |
| `key <keycode>` | 4=BACK 3=HOME 66=ENTER 67=DEL |
| `text "内容"` | 输入文字，需副屏上有输入焦点。纯 ASCII 逐字注入；含中文等非 ASCII 时整串**粘贴**（会覆盖手机剪贴板） |
| `start <包名>` | 同 run-app.sh |
| `window x=.. y=.. w=<dp>` | 移动/缩放小窗 |
| `hide` / `show` | 隐藏小窗（移出屏幕、不可触摸，**副屏继续运行，agent 仍可操作和截图**） |
| `takeover 1\|0` | 手动开关人类接管 |

完整接口见 `references/agent-api.md`。

## 推荐的 agent 工作流
1. `start.sh` → 记下 `DISPLAY_ID`。
2. `run-app.sh <包名>` 启动目标 App，`sleep 2~3`。
3. 循环：`shot` → 看图决定动作 → `tap/swipe/text/key` → 再 `shot` 验证。**每步操作后必须截图确认**，不要盲点。
4. 需要时 `hide` 让小窗不挡主屏，任务结束 `stop`。

## 人类接管规则
- 人手触摸小窗 → 自动接管，agent 的操作返回 **HTTP 423 `human has control`**，空闲 3 秒后自动交还。
- 收到 423：**不要重试轰炸**。等待数秒再试，或告知用户"你正在操作，请说一声继续"。
- 仅在用户明确要求时才加 `force=1` 抢控制权。
- `takeover 1` 可让用户长时间独占，`takeover 0` 交还。

## 坐标
副屏默认 540x960。`tap/swipe` 的坐标就是 `shot` 得到的图片像素坐标（图和副屏同尺寸）。

## 已知限制（如实告知用户）
- 仅在 Pixel 4a / Android 13 验证；`FLAG_SECURE` 内容（银行、密码页）是否被遮挡**未验证**。
- 手机锁屏时无法使用，小窗被系统标为不可见。
- 视频走 H.264，比直通 Surface 多约几十毫秒延迟（估算，未实测）。
- `text` 已实测 Chrome 地址栏英文与中文可用；其它 App/控件未逐一验证。中文走剪贴板粘贴，会覆盖手机剪贴板；粘贴不生效的控件（如禁用粘贴的密码框）无法输入。
- adb 无线调试在重启手机、切换 WiFi 后需要重新连接。
- 本版本需要 adb 连接（电脑或本机无线调试）来拉起 server；**尚未实现脱离 adb 的自启动**。
- 副屏里的 App 可能因系统资源紧张被杀。

## 故障排查
| 现象 | 处理 |
|---|---|
| `ERR: 无在线设备` | 检查无线调试是否开着，`adb connect <ip:port>`；设 `ADB_CONNECT=ip:port` |
| `DISPLAY_ID` 没输出 / server 日志异常 | `adb shell cat /data/local/tmp/sc/server.log`；确认 install.sh 成功 |
| `status` 里 `connected:false` | 小窗没建好：`stop.sh` 后重新 `start.sh`；确认悬浮窗权限已授予 |
| 返回 503 `not connected` | 同上；server 或桥已退出 |
| 截图是 0 字节 / 失败 | 手机锁屏或熄屏，先解锁 |
| App 启动失败 `SecurityException` | 副屏必须有 `PUBLIC` 标志（本 skill 已内置），确认用的是 start.sh 起的屏 |
| `/data/local/tmp` 下 jar 消失 | 本 skill 放在 `/data/local/tmp/sc/` 子目录；重新 `install.sh` |
| adb 卡死无响应 | `adb kill-server`，或换 `ANDROID_ADB_SERVER_PORT` 重启 |

## 目录
- `scripts/`：`install.sh` `start.sh` `stop.sh` `run-app.sh` `agent.sh` `common.sh` `device-run.sh`
- `assets/`：`skilled.apk`、`scrcpy-server.jar`（scrcpy 5.0.1，本机编译，Apache-2.0）、`skilled-shell.jar`（TCP 桥）
- `references/`：`setup.md`、`agent-api.md`、`architecture.md`

## 来源与许可
scrcpy © Genymobile，Apache-2.0。源码与构建脚本：https://github.com/OpenMinis/scrcpy-skilled（私有）。
