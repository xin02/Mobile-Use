# agent-mobile-use-mi9 — 小米 9 / Android 9 适配版

将 [AcidGr/agent-mobile-use](https://github.com/AcidGr/agent-mobile-use)（原为 Android 15/16 + 一加 ACE6T 定制）适配到 **小米 9（Android 9 / API 28 / MIUI 10.2 / Magisk 30.7）**，并打通**单手机闭环**：手机 chroot Ubuntu 内运行 DSH (DeepSeek Harness)，Agent 通过本机网关操控物理主屏，全程无 PC 依赖。

> 上游协议 MIT，本项目延续 MIT。改动均为原创探针实测后得出，详见 [docs/小米9-AI操控手机-适配交付文档.md](docs/小米9-AI操控手机-适配交付文档.md)。

## 目录结构

```
├── agent-mobile-use/        # 上游完整源码 + Android 9 适配修改（见下方差异清单）
├── dsh-preset-mobile-use/   # 上游 DSH 预设 + {{cwd}} 模板变量兼容修复
├── vd-server-mi9/           # 全新重写的 Android 9 兼容网关（Go，REST 契约与上游一致）
├── magisk-module/           # 可直接刷入的 Magisk 模块（含开机自启 + 守护自愈）
├── tools/zipmod/            # 模块打包小工具（生成正斜杠路径的 zip）
└── docs/                    # 交付文档 / DSH 预设注册补丁
```

## 与上游的关键差异（为什么不改就跑不起来）

| 位置 | 问题（Android 9 上） | 修复 |
|---|---|---|
| `vd-tool-java/DaemonMain.java` | MIUI `InterceptorProxy` 在 `systemMain()` 内要求 main Looper，守护进程启动即 NPE | 启动早期 `prepareMainLooper()` + 预热拦截器 |
| 同上 | DisplayManagerService 校验 `packageName must match calling uid`，root 无法创建副屏 | 绑定端口后降权到 uid 1000（system） |
| 同上 | 非 root uid 被 SELinux 拒绝 `socket()/bind()` | **先以 uid 0 绑定 3071/3072，再降权**（accept/write 不受影响） |
| 同上 | flags 含 API 29+/34+ 隐藏位，Android 9 未定义行为 | 按 SDK_INT 分支裁剪，支持 `VD_FLAGS` 环境变量覆盖 |
| 同上 | Android 9 的 `screencap` 无法寻址 Java 侧副屏 | ctrl 通道新增 `screenshot`（ImageReader 帧缓存）与 `launch`（uid 1000 发起 `am start --display`） |
| 同上 | `input text` 无法携带中文 | ctrl 通道新增 `clip <base64>`（system uid 写剪贴板，网关跟 KEYCODE_PASTE） |
| `vd-tool-java/ToolMain.java` | `getWindowsOnAllDisplays` 为 API 30+，Android 9 直接 NoSuchMethodException | 回退路径：`getWindows()` + `dumpsys window`/顶层包名归属过滤；MIUI 10 窗口类型误判修复 |
| `ksu-module/bin/run_daemon.sh` | `BOOTCLASSPATH` 指向不存在的 `/apex`（Android 9 无 APEX） | 优先从 `system_server` 的 `/proc/<pid>/environ` 取真实值，旧系统回退 `/system/framework` |
| `vd-server-go`（整目录弃用） | 触控/观测链路依赖 `input -d`（Android 10+）、`/apex` 等 | 由 `vd-server-mi9/` 全新实现：`uiautomator dump`（带重试）+ `input` + `screencap` + 剪贴板粘贴，REST 契约不变 |
| `dsh-preset-mobile-use/agent.cordis.yml` | DSH 0.2 不支持 `{{cwd}}` 变量，任务直接报错 | 替换为固定工作区路径 |

**Android 9 硬限制**（探针实测，无法绕过）：副屏无障碍树只暴露主屏窗口、输入焦点只在 display 0——因此最终形态为**前台模式**自动化；副屏可创建、可显示、可截图，但不能交互。

## 快速开始

### 前置

- 小米 9 已解锁 + Magisk 30.x（Zygisk）
- PC：Go ≥1.21（交叉编译 android/arm64）、Android SDK（javac + d8）、adb

### 构建

```bash
# 1) Java dex（含 MIUI/Android 9 修复）
cd agent-mobile-use/vd-tool-java && bash build_win.sh   # 产物: bin/agent_vd.dex

# 2) Go 网关
cd ../../vd-server-mi9
GOOS=android GOARCH=arm64 CGO_ENABLED=0 go build -trimpath -ldflags="-s -w" -o vd_server_mi9 .

# 3) Magisk 模块 zip（bin/ 内放好上一步产物后）
cd ../tools/zipmod && go run . ../../magisk-module ../../agent_mobile_use_mi9.zip
```

### 刷入与部署

```bash
adb push agent_mobile_use_mi9.zip /data/local/tmp/
adb shell "su -c 'magisk --install-module /data/local/tmp/agent_mobile_use_mi9.zip'"
```

模块开机自动拉起：helper(3072) → 网关(3070) → chroot DSH(3080) → UI 代理(3099)，带 30s 守护自愈。
chroot Ubuntu + Node 22 + DSH + DeepSeek API Key 的完整部署步骤见 [docs/小米9-AI操控手机-适配交付文档.md](docs/小米9-AI操控手机-适配交付文档.md) 第五节。

### DSH 预设注册（关键！）

本仓库 `docs/dsh-preset-cordis.patch.yml` 必须落到
`/data/local/ubuntu/root/.dsh/profiles/web/cordis.patch.yml`——
该 DSH 版本**不扫描** `~/.dsh/.agent-presets/` 目录，没有这个补丁，预设下拉里就没有
"Mobile Use 模式"，模型会因为拿不到 `mobile` 工具而回复"干不了"。

### 使用

手机浏览器打开 `http://127.0.0.1:3099/` → 新建会话 → 预设选 **Mobile Use 模式** → 下指令。
涉及敏感操作时 Agent 会通过 `ask_user` 先确认（输入 1/2 回答）。

## 已验证

真机（小米 9）端到端验收：*「打开小米便签，新建一条便签，内容写：AI自动化测试OK」*
→ Agent 1 轮 16 步完成：启动应用 → 新建 → 中文注入 → 保存 → 回列表复核一致 → 切回待机。

## 已知限制

1. 无后台副屏交互（Android 9 硬限制，详见交付文档）
2. 屏幕需点亮；有 PIN 锁屏时首次需人工解锁
3. `observe` 依赖 `uiautomator dump`，单次 1.4~3.7s（已内置重试）

## 致谢

- [AcidGr/agent-mobile-use](https://github.com/AcidGr/agent-mobile-use) — 手机端模块与网关原型
- [AcidGr/dsh-preset-mobile-use](https://github.com/AcidGr/dsh-preset-mobile-use) — DSH 预设封装
- [deepseek-harness](https://github.com/deepseek-ai) — Agent 框架

## License

MIT
