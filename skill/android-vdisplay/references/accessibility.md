# 为什么当前副屏无法读取控件树（无障碍 / uiautomator）

## 实测结论
**截至当前版本，无法通过无障碍接口或系统命令行读取副屏内的控件树。agent 必须使用视觉截图 + 像素坐标。**

在 Pixel 6（Android 17, API 37）上做过三路完整对比实测：

### 1. `android-a11y-cli ui dump`（Minis 内置无障碍工具）
- **即使指定参数**：
  ```sh
  android-a11y-cli ui dump --display <副屏ID>
  android-a11y-cli ui dump --package com.google.android.calculator
  ```
- **实际返回**：无论加不加 `--display`，底层均未遍历其他 display 的窗口树，永远返回**主屏的 45 个节点**（包含 `com.android.systemui` 状态栏、`com.openminis.app`、主屏上的悬浮窗 `app.openminis.vdisplay`）。副屏上的计算器节点一个也拿不到。
- **搜文字**：`android-a11y-cli ui find --text "7"` 实测返回 `count: 0`。
- **虽然 `ui info` 能识别到包名**（返回 `packageName: "com.google.android.calculator"`），但其 `windows` 列表里只有主屏的窗口 ID，拿不到节点树。

### 2. `uiautomator dump --display <ID>`（系统底层工具）
- 命令支持 `--display <ID>` 参数，但实测在 Android 17 上被系统底层忽略。
- 当副屏是计算器、主屏是设置/时钟时：
  ```sh
  uiautomator dump --display 81 /sdcard/ui.xml
  ```
  抓出来的 XML 永远是主屏当前获焦的 App（设置/时钟），节点数为 76，副屏应用完全未被采集。

### 3. 系统无障碍框架底层其实「看得到」副屏
- 在 adb 中执行 `dumpsys accessibility`：
  - `Display[0]` 对应主屏窗口
  - `Display[81]` 能看到 `Calculator` 的窗口句柄、任务栏与状态栏
- 说明 Android 框架层支持多屏无障碍（`AccessibilityService.getWindowsOnAllDisplays()` 公开 API），只是 **Minis 宿主目前的 `MinisAccessibilityService` 尚未遍历非 0 display 的根节点**。

## 对 Agent 的行动指导
- **绝对不要尝试**以下无用轮数：
  - 换用各种 dump 参数（`-d`、`--window`、`--container`）
  - 尝试从 `/sdcard/window_dump.xml` 抓取副屏内容
  - 反复调用 `find --text` 搜副屏文字
- **唯一的正解路径**：
  ```sh
  sh $SK/agent.sh shot /tmp/s.jpg jpg 70
  ```
  读取图片并由视觉模型识别点击目标，换算为坐标后调用 `sh $SK/agent.sh tap x y`。
- 后续如果 Minis 宿主或副屏 APK 接入了 `getWindowsOnAllDisplays`，本 skill 会同步更新 `/ui` 接口并在此文档标注。
