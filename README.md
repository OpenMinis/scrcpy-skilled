# scrcpy-skilled

在 Android 设备**本机**创建虚拟屏幕、在主屏以悬浮小窗（画中画）展示，并可被接管/自动化操作。
基于 [Genymobile/scrcpy](https://github.com/Genymobile/scrcpy)（作为 git submodule，位于 `third_party/scrcpy`，不改其源码，仅复用思路与代码）。

## 架构
```
┌─ app/ (普通 APK, SYSTEM_ALERT_WINDOW) ─┐      ┌─ shell/ (shell 身份, app_process) ─┐
│ 悬浮窗 SurfaceView                      │      │ 注入输入 injectInputEvent(displayId)│
│ createVirtualDisplay → 小窗 Surface     │─TCP─►│ am start --display N、截图、自动化   │
│ 小窗触摸 → 坐标映射 → 发给 shell        │ 本机 │ 参考 third_party/scrcpy server 实现 │
└─────────────────────────────────────────┘      └────────────────────────────────────┘
```

## 目录
| 路径 | 说明 |
|---|---|
| `third_party/scrcpy` | scrcpy 子模块（只读依赖） |
| `app/` | 悬浮窗 APK（待从 PoC 迁入） |
| `shell/` | shell 身份服务（待从 PoC 迁入） |
| `scripts/` | 构建、部署、`adb-safe.sh`（带超时自愈的 adb 包装） |
| `skill/android-vdisplay` | 可分发的 Minis skill（含 APK、jar、脚本、SKILL.md）；产物由 scripts/ 构建后拷入 assets/ |
| `docs/` | 设计与实验记录，见 `docs/NOTES.md` |
| `experiments/` | 已验证的 PoC 原始代码（overlay-exp：shell 图层浮窗；pip-exp：虚拟屏+小窗+接管） |

## 克隆
```
git clone --recurse-submodules git@github.com:OpenMinis/scrcpy-skilled.git
# 或：git submodule update --init --depth 1
```

## 状态
PoC 已在 Pixel 4a / Android 13 验证：小窗内运行虚拟屏上的计算器，真手指点小窗可被注入响应。详见 `docs/NOTES.md`。
