# 实验记录（Pixel 4a, Android 13）

## 实验 A：shell 身份图层浮窗
app_process 里用 `SurfaceControl.Builder` + `Transaction.setLayer(INT_MAX)` 建顶层图层，可见但**不可点击**。`drawText` 会 abort（字体未初始化）。

## 实验 C：虚拟屏 + 悬浮窗 + 接管
- APK 悬浮窗放 SurfaceView，自己 `createVirtualDisplay`（`OWN_CONTENT_ONLY|PUBLIC|SUPPORTS_TOUCH`）输出到它。
- shell 端 Srv 监听 127.0.0.1:37911（T/K/S 文本协议），用 `InputManager.injectInputEvent` + `InputEvent.setDisplayId` 注入。
- 验证：`am start --display N` 启动计算器进虚拟屏；点小窗输入 8、3 → 显示 483。

## 关键坑
1. 虚拟屏不加 `PUBLIC`，shell 往里 `am start` 会 SecurityException。
2. `TRUSTED` flag 普通 App 被拒（ADD_TRUSTED_DISPLAY），没有也够用。
3. `am start-foreground-service` 必须 5 秒内 `startForeground()`，否则进程被杀、虚拟屏消失。
4. 后台 `startservice` 被拦，用 `start-foreground-service`。
5. 不要 `pkill -f` 自己的 shell；用脚本文件 + `nohup setsid` 启动 Srv。
6. adb server 易卡死 → `scripts/adb-safe.sh`。
7. screencap 走 exec-out 偶尔截断 → 用 `shell screencap` + `pull`。

## 待做
Srv 本机自拉起（无线调试自连/Shizuku）、读屏取帧（ImageReader）、小窗拖动缩放、多点触控、文字输入、锁屏表现、正式工程化（gradle）。

## 方案 1（H.264，scrcpy server jar 零改动）已验证 — 2026-11-04
链路：scrcpy server(`new_display`, trusted 虚拟屏 + 系统装饰) → `@scrcpy_00001234` → **Bridge**(shell 身份, TCP 桥) → 127.0.0.1:27183 → APK `ScrcpyClient` → `MediaCodec` 解码到悬浮窗 SurfaceView；触摸经控制通道(ControlMessage type 2) 回传。
验证：小窗里计算器出画面；真手指依次点 7、5、5，显示 755。截图 `docs/plan1-poc.png`。
- 关键验证：普通 APK 连 shell 进程的 abstract socket → `Permission denied`（SELinux），所以必须 TCP 桥。
- 桥顺序：scrcpy 在 tunnel_forward 下按 video、control 顺序 accept，桥与客户端必须同序连接。
- 控制消息要在非主线程写 socket（NetworkOnMainThreadException）。
- `/data/local/tmp` 根目录的 jar 会消失，放 `/data/local/tmp/sc/`。
- 构建：`scripts/build-scrcpy-server.sh`、`scripts/build-apk.sh`；一键部署：`scripts/restart-all.sh`。
- 未做：start_app 走控制通道（现用 am start --display）、小窗拖动缩放、文字输入、agent 接口、本机自拉起、桥的多会话/重连。
