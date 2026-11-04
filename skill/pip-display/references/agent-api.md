# Agent 接口（APK 内置，127.0.0.1:28080）

APK 启动后在设备本机监听。电脑上用 `adb forward tcp:<本机端口> tcp:28080` 访问；设备内的 agent 直接访问 127.0.0.1:28080。坐标为虚拟屏像素（默认 540x960）。

| 路径 | 说明 |
|---|---|
| `/status` | `{connected,width,height,humanActive,manualTakeover}` |
| `/screenshot` | PNG（PixelCopy 自小窗 SurfaceView，尺寸=虚拟屏） |
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
