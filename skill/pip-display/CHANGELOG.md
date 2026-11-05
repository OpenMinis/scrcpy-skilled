# 更新说明

> **遇到任何错误，请先更新到最新版再试一次**：重新安装最新的 pip-display 包后运行 `start.sh`，脚本会自动比较设备上 APK 与 skill 自带版本，不一致则重装。查看版本：`sh scripts/agent.sh version`（返回 skill 版本、设备 APK 版本、是否一致）。APK 也可直接查询：`/status` 与 `/version` 接口含 `version`、`versionCode`、`protocol`、`sdk`、`device`。

## 0.5.4
- 光晕改为 GPU 着色器（AGSL）一次绘制，每帧零分配，帧耗时约 12ms、无掉帧
- 内部光晕 = 屏幕呼吸灯：原地渐显渐隐，不再移动；向内的渐变用圆润等值线，中间没有直角镂空
- 旋转彩虹边框 + 绕行高光保留
- 右上角停止改为红色圆形图标按钮（无文字）
- AI 操作中：点击画面任意位置接管，拖动画面移动悬浮窗；中央提示随呼吸闪烁
- 标题胶囊带玻璃质感；圆角、默认窗口尺寸略缩小
- run-app.sh 修复 dumpsys 管道导致的卡死；start.sh 等待 server 更久并自动重试一次

## 0.3.6
- 悬浮窗靠近屏幕左右边缘松手 → 自动折叠吸附，只留一个带箭头的小拉手；点拉手展开，拉手可上下拖动
- 画面改用 TextureView，四角真正圆角；标题叠在画面上，不占高度
- 光晕：Siri / Apple Intelligence 风格，彩色柔光团沿边缘游走并向内铺满画面，运动更快；去掉外阴影，保留旋转彩虹边框
- AI 操作中居中显示「立即停止」「接管」两颗等大胶囊（带图标）；接管后显示「交还 AI」；立即停止后 agent 操作返回 423，直到用户点「交还 AI」
- 标题可由 agent 设置：`agent.sh title "正在做什么"`
- 虚拟屏默认与物理主屏同尺寸/dpi；截图与点击统一用参考宽度 540 的坐标（`REF_W` 可改），不用关心实际分辨率
- `start.sh`：自动选空闲端口（27183 被占用不再失败）、等待视频帧到达才返回、失败给出 stage/hint/log、自动比对并升级 APK
- `run-app.sh`：确认 App 真的在副屏前台；`agent.sh shot` 校验 PNG；adb 卡死自动换端口自愈
- `/status` 新增 `ready`、`frames`、`version`、`stopped`；新增 `/version`、`/title`、`/resume`

## 0.1.0
- 首个版本：scrcpy 虚拟屏 + 悬浮窗 + HTTP agent 接口
