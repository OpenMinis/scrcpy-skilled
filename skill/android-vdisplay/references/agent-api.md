# Agent 接口（APK 内置，127.0.0.1:28080）

APK 启动后在设备本机监听。电脑上用 `adb forward tcp:<本机端口> tcp:28080` 访问；设备内的 agent 直接访问 127.0.0.1:28080。坐标为虚拟屏像素（默认 540x960）。

| 路径 | 说明 |
|---|---|
| `/status` | `{connected,ready,frames,rendered,dropped,decodeErrors,width,height,closed,closedReason,idleMs,humanActive,manualTakeover,...}`，见下方 ready 语义 |
| `/screenshot` | PixelCopy 自小窗 SurfaceView。参数：`w=宽`、`scale=0.1~1`、`fmt=png\|jpg\|webp`、`q=30~100`（jpg/webp 的质量）。响应头 `X-Timing-Ms` 给出 capture/scale/encode 耗时 |
| `/ping` | 空操作，用来量传输开销 |
| `/tap?x=&y=` | 点击 |
| `/swipe?x1=&y1=&x2=&y2=&ms=300` | 滑动 |
| `/key?code=4` | 按键（Android keycode） |
| `/text?s=hello` | 输入文字 |
| `/start?pkg=com.android.chrome` | 在虚拟屏启动 App |
| `/takeover?on=1\|0` | 人类接管开关。接管期间 agent 操作返回 HTTP 423 |

人类触摸小窗会自动接管，空闲 3 秒后交还；agent 可加 `force=1` 强制操作。

## 实测（Pixel 4a, Android 13）
- tap/key/swipe：计算器 AC、7、8、9、退格 → 显示 78，符合预期。
- screenshot：540x960 PNG，画面干净（不含主屏内容）。
- start：Chrome 启动到虚拟屏 display 19。
- takeover=1 时 tap 返回 423；force=1 放行；on=0 恢复。
- **未验证**：真手指触摸小窗自动触发接管（测试时手机处于锁屏，小窗 NOT_VISIBLE，收不到触摸）。

## 补充
- `/window?x=&y=&w=<dp>`：移动/缩放小窗；`/window?hide=1` 移出屏幕且不可触摸（副屏继续运行，agent 仍可操作与截图）；`hide=0` 恢复。
- `/text?s=`：ASCII 逐字注入；含非 ASCII 走剪贴板粘贴（覆盖手机剪贴板）。
- 以上接口监听设备内 127.0.0.1:28080；skill 的 `agent.sh` 通过 adb forward 调用。

## `ready` / `frames` / `rendered`（0.11.0）
- `frames`：从 scrcpy 视频流收到的**数据包**数（旧版 `ready` 的依据）。包到了不代表画面出来了：解码器可能没拿到 config/关键帧，或 Surface 失效，此时 `frames` 持续增长而截图是纯黑。
- `rendered`：解码器**真正输出并以 render=true 释放**的帧数。
- `ready = connected && rendered > 0`，即“已经画出过画面”。`start.sh` 以此为成功标准。
- `dropped`：因解码器输入缓冲超时而丢弃的包数（config/关键帧会重试约 500 ms，仍失败则向 server 发 RESET_VIDEO 要新关键帧）。`decodeErrors`：解码异常次数（原因在 `error` 与 logcat 的 `SKILLED` 标签）。
- Surface 销毁时 `rendered` 归零，新 Surface 绑定并出新画面后恢复。
