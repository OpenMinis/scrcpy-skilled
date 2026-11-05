# 架构与实现要点

## 组件
| 组件 | 运行身份 | 作用 |
|---|---|---|
| scrcpy server (`scrcpy-server.jar`) | adb shell | 创建 **trusted 虚拟屏**（带系统装饰/输入法），H.264 编码，处理控制消息（触摸/按键/文字/启动 App） |
| Bridge (`skilled-shell.jar`) | adb shell | TCP 桥：连 `@scrcpy_00001234`，映射到 `127.0.0.1:27183` |
| `skilled.apk` | 普通 App（悬浮窗权限） | 连桥、解码 H.264 到悬浮窗 SurfaceView、转发触摸、内置 HTTP 接口(28080) |

## 为什么要 TCP 桥
普通 App 连 shell 进程创建的 abstract socket 会被 SELinux 拒绝（`Permission denied`，已实测），但可以连 `127.0.0.1` TCP。scrcpy server 因此保持零改动。

## 连接顺序
scrcpy 在 `tunnel_forward=true` 下按 **video → control** 顺序 accept，Bridge 与 APK 必须同序连接。流格式：`[dummy 1B][设备名 64B][codec id 4B]`，之后循环 `[12B 帧头 + payload]`；帧头 8B `ptsAndFlags`（bit63 session，bit62 config，bit61 keyframe）+ 4B 长度；session 头为 `flags(4)+w(4)+h(4)`。

## 虚拟屏参数
`new_display=WxH/dpi vd_system_decorations=true`；`cleanup=false power_off_on_close=false power_on=false stay_awake=false`，退出时不改主屏电源/常亮设置。

## 从源码重新构建
仓库 OpenMinis/scrcpy-skilled：`scripts/build-scrcpy-server.sh`（本机编译 scrcpy server，手写 AIDL stub，因为 x86 的 aidl 在 ARM 上跑不了）、`scripts/build-apk.sh`。

## 已验证的坑
- shell 往虚拟屏 `am start` 需要虚拟屏带 `PUBLIC`，scrcpy 已带。
- 前台服务必须 5 秒内 `startForeground()`，否则进程被杀、虚拟屏消失。
- 控制消息要在非主线程写 socket。
- `/data/local/tmp` 根目录的 jar 会消失，放 `/data/local/tmp/sc/`。
