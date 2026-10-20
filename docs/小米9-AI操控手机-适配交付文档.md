# 小米 9（Android 9）AI 操控手机 —— 适配交付文档

> 设备：小米 9（cepheus，序列号 d2813f4d）｜Android 9 / API 28 / MIUI V10.2.35.0.PFACNXM｜内核 4.14.83｜Magisk 30.7（Zygisk）
> 改造对象：AcidGr/agent-mobile-use（原 Android 15/16 + 一加 ACE6T 定制）+ AcidGr/dsh-preset-mobile-use
> 交付时间：2026-10-20　状态：**单手机闭环已跑通**（前台模式）

---

## 一、结论先说

| 原项目能力 | 小米 9 实测结果 | 说明 |
|---|---|---|
| 虚拟副屏创建 | ✅ 可用 | 需降权到 uid 1000 才能通过 DisplayManagerService 的包名校验 |
| 应用拉到副屏运行 | ✅ 可用 | `am start --display N` 必须由 uid 1000 进程发起 |
| 副屏截图 | ✅ 可用 | ImageReader 帧缓存方案；`screencap -d` 在 Android 9 不可用 |
| 副屏无障碍树读取 | ❌ 不可用 | Android 9 无障碍服务只暴露主屏窗口（API 30 才有 getWindowsOnAllDisplays） |
| 副屏触控注入 | ❌ 不可用 | Android 9 输入焦点只在 display 0；`OWN_FOCUS` 标志是 Android 10+ |
| **主屏（前台）全能力** | ✅ **全部可用** | observe / click / swipe / key / set_value / launch_app / screenshot |

**因此最终形态：前台模式（物理主屏）自动化，单手机闭环。**
后台副屏"静默执行"在 Android 9 上无法完整实现（能看不能点），除非把原项目的 LSPosed system_server Hook 移植到 Android 9 —— 属"平台移植"级工程，本次未做。

---

## 二、改造后的架构（全部跑在手机内）

```
┌──────────────────────────────────────────────┐
│ 小米 9 · Android 9                            │
│                                              │
│  chroot Ubuntu 24.04 (/data/local/ubuntu)    │
│   └─ Node 22.19 + DSH (dsh web :3080)        │
│       └─ mobile-use 预设（.dsh/.agent-presets）│
│                    │ HTTP 127.0.0.1:3070      │
│                    ▼                          │
│  vd_server_mi9（Go 网关，模块自启）            │
│    ├─ uiautomator dump → 扁平控件树            │
│    ├─ input tap/swipe/keyevent → 触控          │
│    ├─ screencap -p → 截图                     │
│    └─ 剪贴板+PASTE → 中文文本注入              │
│                    │ 127.0.0.1:3072           │
│                    ▼                          │
│  DaemonMain（agent_vd.dex, uid 1000, helper） │
│    └─ ClipboardManager / 输入注入             │
└──────────────────────────────────────────────┘
```

模块目录：`/data/adb/modules/agent_mobile_use_mi9/`
运行目录：`/data/local/tmp/agent/`（日志、状态、截图、dump）

---

## 三、改造点清单（逐条，含原因）

### 1. `vd-tool-java/src/com/agent/DaemonMain.java`

| 改动 | 原因（实测） |
|---|---|
| `Looper.prepareMainLooper()` 替代 `Looper.prepare()`，并预初始化 `miui.contentcatcher.InterceptorProxy` | MIUI 10 在 `ActivityThread.systemMain()` 里用 `new Handler(Looper.getMainLooper())`，普通 prepare 会让 `getMainLooper()` 为 null → 进程直接 NPE 崩溃 |
| 启动即 `setGid/setUid(1000)` 降权到 system | Android 9 DisplayManagerService 校验 `packageName must match the calling uid`，root（uid 0）没有包名 → SecurityException；uid 1000 对应包名 `android`，与系统 Context 一致 |
| 副屏 flags 按 SDK 分支：API<29 用 `PUBLIC\|OWN_CONTENT_ONLY`（0x9） | `SHOULD_SHOW_SYSTEM_DECORATIONS`(API29+)、`TRUSTED`(API34+)、`OWN_FOCUS`/`STEAL_TOP_FOCUS_DISABLED`(Q+) 在 P 上非法 |
| **先以 root 绑定 3071/3072，再降权** | 降权后 `socket()`/`bind()` 返回 EACCES（SELinux，任何非 0 uid 都被拒）；已绑定的 socket 在降权后 accept/read/write 正常 |
| 新增 ctrl 命令 `launch <display> <pkg>/<act>`（uid 1000 调 `am start -f 0x18000000`） | root/shell 发起 `launchDisplayId` 被 `SafeActivityOptions.checkPermissions` 拒绝；uid 1000 放行。`-f 0x18000000`（NEW_TASK\|MULTIPLE_TASK）避免被判定"已在前台" |
| 新增 ctrl 命令 `screenshot <path>`（ImageReader 帧缓存 + 节流 300ms） | `screencap -d` 只认 SurfaceFlinger display token，Java displayId 不可用；静态画面不再产生新帧，故缓存最近一帧 |
| 新增 ctrl 命令 `clip <base64>` | 中文输入唯一可行路径：特权进程写剪贴板 + KEYCODE_PASTE（`input text` 只能 ASCII） |
| 新增 ctrl 命令 `tap/swipe/key`（InputManager 反射注入） | `input -d` 在 Android 9 不存在；保留作为备选通道 |
| 新增 `VD_NO_DISPLAY=1` helper 模式 | 前台模式不需要副屏，只保留剪贴板/注入能力，省内存 |
| 状态/停止文件改到 `/data/local/tmp/agent/` | 降权后无法在 `/data/local/tmp` 根目录创建文件，改用 0777 子目录 |

### 2. `ksu-module/bin/run_daemon.sh`（模块内 `bin/run_daemon.sh`）

| 改动 | 原因 |
|---|---|
| `BOOTCLASSPATH` 优先从 `/proc/<system_server>/environ` 读取 | Android 9 没有 `/apex`，原脚本硬编码 APEX 路径 → `app_process` 直接起不来 |
| 增加 SDK<29 的 `/system/framework` 回退清单（含 `miui-framework.jar`） | `/proc` 读不到时的兜底 |
| `ANDROID_ART_ROOT`/`I18N_ROOT`/`TZDATA_ROOT` 仅在目录存在时导出 | 同上 |

### 3. 新增 `vd-server-mi9/main.go`（Go 网关，替代原 vd-server-go）

原 `vd-server-go`（2860 行）大量依赖 Android 10~15 原语：`input -d`、`RootTask` 解析、`topResumedActivity` 字段、`cmd activity unfreeze`、cgroup v2 冻结、副屏栈迁移…… 在 Android 9 上全部失效且多为静默失败。

新网关保持**完全相同的 REST 契约**（DSH 预设无需改动），内部换成 Android 9 可用原语：

| 能力 | 实现 |
|---|---|
| observe | `uiautomator dump` + XML 解析，输出与原项目一致的扁平行格式（含 `id type "name" x1,y1,x2,y2 flags`），4 次重试避开 MIUI `null root node` |
| click / swipe / key | `input tap/swipe/keyevent`（主屏，无需 -d） |
| set_value | 点目标 → 按已有文本长度发 DEL 清空 → 剪贴板 + PASTE（支持中文） |
| launch_app | `cmd package resolve-activity` + `am start -n`，失败回落 `monkey` |
| screenshot | `screencap -p`（自动唤醒后截图） |
| switch_mode | foreground / idle 正常；background 明确回退为 foreground 并说明原因 |
| 辅助 | `ensureAwake()`：检测 Asleep → KEYCODE_WAKEUP + dismiss-keyguard（否则截图全黑、dump 为空） |

### 4. `module-mi9/`（Magisk 模块，新增）

- `service.sh`：开机等待 `sys.boot_completed` → 起 helper（3072）→ 起网关（3070）→ 起 chroot+DSH（3080）→ 30 秒守护循环自愈
- `bin/chroot_start.sh`：挂载 proc/sys/dev/pts、写 DNS(223.5.5.5)、`setsid` 启动 `dsh web --no-open`、回显带 token 的访问地址
- `customize.sh`：权限设置 + 复制一份到 `/data/local/tmp/agent`（模块损坏时可手动拉起）

### 5. DSH 侧（chroot 内，无需改代码）

- Node 22.19.0（arm64）+ npm 10.9.3
- `npm i -g @deepseek-ai/dsh`（registry 用 npmmirror）
- 预设 `dsh-preset-mobile-use` 首次安装：先 `cp preset/mobile-use/* → ~/.dsh/.agent-presets/mobile-use/`，再 `bash install.sh`（脚本只做更新校验，不建目录）
- `check-preset.mjs` 输出 `OK: 1 tools mount cleanly — mobile`

---

## 四、所需权限

| 权限/能力 | 用途 | 获取方式 |
|---|---|---|
| Root（Magisk 30.7） | 起 app_process、挂 chroot、写系统目录、injec 事件 | 已具备 |
| uid 1000（system）降权 | 副屏创建/应用上屏/剪贴板写入的权限前提 | 进程内 `Process.setUid`，root 可自降 |
| SELinux Enforcing 下 magisk 域 | 保持现状即可；无需 setenforce 0 | 已验证全程 Enforcing 可用 |
| `/data/local/tmp/agent` 0777 | 降权进程写状态/截图/dump | `service.sh` 自动创建 |
| Zygisk/LSPosed | 仅原项目的通知 Hook 需要 | **本次未使用**，不装也能跑 |
| DeepSeek API Key | DSH 调模型 | 在 DSH Web 设置页填写 |

---

## 五、操作步骤（当前设备已完成，供重装/新机复现）

```bash
# 0. 前置：手机已 root，adb 可用
ADB=/d/tools/adsdk/platform-tools/adb.exe

# 1. 安装模块（二选一）
#    a) Magisk App 刷入 agent_mobile_use_mi9.zip
#    b) 命令行
$ADB push agent_mobile_use_mi9.zip /data/local/tmp/
$ADB shell "su -c 'magisk --install-module /data/local/tmp/agent_mobile_use_mi9.zip'"
# 命令行安装会进 modules_update，立即生效可手动：
$ADB shell "su -c 'rm -rf /data/adb/modules/agent_mobile_use_mi9; mv /data/adb/modules_update/agent_mobile_use_mi9 /data/adb/modules/'"
$ADB shell "su -c 'sh /data/adb/modules/agent_mobile_use_mi9/service.sh'"

# 2. 验证网关
$ADB forward tcp:3070 tcp:3070
curl -s http://127.0.0.1:3070/api/status          # {"device":"xiaomi9-android9","sdk":28,...}
curl -s -X POST http://127.0.0.1:3070/api/action -H 'Content-Type: application/json' -d '{"action":"observe"}'

# 3. chroot + DSH（模块已内置，手动启动见下）
$ADB shell "su -c 'sh /data/adb/modules/agent_mobile_use_mi9/bin/chroot_start.sh'"
# 输出形如：dsh web up: http://127.0.0.1:3080/?token=xxxxx

# 4. 手机浏览器打开该 URL → 设置里填 DeepSeek API Key → 右上角预设选 mobile-use
```

---

## 六、使用方式（单手机闭环）

1. 手机浏览器访问 `http://127.0.0.1:3080/?token=<每次启动随机>`
   - token 查看：`adb shell su -c 'grep -o "token=[A-Za-z0-9_-]*" /data/local/ubuntu/root/dsh.log | tail -1'`
2. 设置页填 DeepSeek API Key（只需一次）
3. 预设选 `mobile-use`
4. 直接下指令，例如：
   - 「打开便签，新建一条，写上：明天10点开会」
   - 「打开设置，看看当前 Android 版本」
   - 「打开抖音，往下滑三条」
5. 语音输入：用系统语音输入法在 DSH 输入框"说话"即可（DSH 本身无语音）

**前置条件**：屏幕需点亮（网关会自动唤醒并尝试解除锁屏）；有锁屏密码时需先手动解锁一次。

### API Key 存放位置（已配置）

DSH 的 `credentials` 服务在本版本中不会解析 `.credentials.yaml` 里的 `refs` 条目（实测 `MISSING_CREDENTIAL`），
**有效路径是启动环境变量**：

- 密钥文件：`/data/local/ubuntu/root/.dsh/deepseek.env`（权限 600）
- 内容：`DEEPSEEK_API_KEY=sk-...`
- `chroot_start.sh` 启动 `dsh web` 前读取该文件并 `export`，因此重启后依然生效
- 更换 Key：改这个文件 → `adb shell su -c 'pkill -x node'` → `sh /data/adb/modules/agent_mobile_use_mi9/bin/chroot_start.sh`

验证方式（单次提问，不启 Web）：

```bash
adb shell "su -c 'chroot /data/local/ubuntu /bin/bash -c \"export PATH=/opt/node/bin:/usr/bin:/bin; export HOME=/root; export DEEPSEEK_API_KEY=\$(sed -n s/^DEEPSEEK_API_KEY=//p /root/.dsh/deepseek.env); cd /root; dsh headless \\\"只回复两个字：收到\\\"\"'"
# 输出：收到
```

---

## 七、实测记录（本次真机验证）

| 项目 | 结果 |
|---|---|
| 副屏创建 | `Virtual Display created successfully! ID: 4~10`（多次） |
| 应用上副屏 | 便签在 Display 8 内正常渲染（截图 137KB，画面完整） |
| 副屏触控 | ❌ 注入落到主屏（InputDispatcher 焦点在 display 0） |
| 副屏无障碍 | ❌ `getWindows` 只返回主屏窗口 |
| 主屏 observe | ✅ 63 节点（抖音界面）/ 8 节点（便签编辑页），1.4~3.7s |
| 主屏 click（按节点 ID） | ✅ 点"新建便签"→弹出云同步对话框→点"暂不"关闭 |
| 中文 set_value | ✅ 清空旧文本 + 写入「你好，这是 AI 自动输入的测试文本 2026」，截图确认渲染正确 |
| swipe / key / apps | ✅ 均返回成功并附最新控件树 |
| screenshot | ✅ 142KB PNG，自动唤醒后不再黑屏 |
| 冷启动模拟 | ✅ 杀掉全部服务后 `service.sh` 拉起 3070/3072/3080 |
| DSH 预设挂载 | ✅ `check-preset.mjs` → `OK: 1 tools mount cleanly — mobile` |

---

## 七点五、排障实录："模型说干不了"（2026-10-20 已解决）

现象：在 DSH 里下指令，Agent 回复"我无法帮你直接打开手机上的应用"，从不调用 `mobile` 工具。

根因（两层）：

1. **mobile-use 预设从未注册**。该 DSH 版本的预设注册表只内置 standard/ptc/minimal/cordis 四种，
   **不扫描** `~/.dsh/.agent-presets/` 目录（README 描述与实际行为不符）。预设下拉里没有
   "Mobile Use 模式"，会话因此拿不到 `mobile` 工具——模型无工具可用，只能说干不了。
   **修复**：在 `~/.dsh/profiles/web/cordis.patch.yml` 用 loader 的 `insert:` 语法内联注册
   `preset-mobile-use`（`@deepseek-ai/dsh-agent-preset`，plugins 首位挂载
   `/root/.dsh/.agent-presets/mobile-use/mobile_plugin.js`，其后内联 agent.cordis.yml 全部条目）。
   注意：补丁条目必须用 `- insert:` 包装，直接写 `- id: preset-mobile-use` 会被警告
   "entry not found" 并跳过（补丁只做按 id 覆盖，不新增）。
2. **persona 模板变量不兼容**。预设的 persona-suffix 用了 `{{cwd}}`，该 DSH 版本只注册
   provider/model 两个变量，任务一发就报
   `unknown prompt variable "{{cwd}}" in section "deployment:persona-suffix"`。
   **修复**：把 `{{cwd}}` 替换为固定路径 `/root/ws`。

端到端验收（修复后第一条真实任务）：

- 任务："打开小米便签，新建一条便签，内容写：AI自动化测试OK"
- Agent 行为：ask_user 询问前台/后台 → launch_app 启动便签 → observe 定位"新建便签"按钮
  → click → set_value 注入中文 → 点"完成编辑"保存 → 返回列表 observe 复核 → 汇报
  （1 轮 16 步、182K tok）
- 结果：便签成功创建，内容与要求完全一致；网关 `actions.log` 留有完整调用轨迹。

**固化**：该补丁已打包进 Magisk 模块（`bin/dsh-cordis.patch.yml`，customize.sh 安装时自动落到
`/data/local/ubuntu/root/.dsh/profiles/web/cordis.patch.yml`），重刷模块不会丢失。

---

## 八、已知限制与后续可做

1. **无后台副屏**（Android 9 硬限制）。若要"不打扰主屏"，唯一路径是把原项目 `HookEntry`（system_server Hook：跨屏承载 + 输入焦点）移植到 Android 9 + LSPosed，工程量约等于平台移植。
2. **屏幕必须点亮且解锁**；有 PIN 时首次需人工解锁。
3. **MIUI 后台清理**：模块内置 30s 守护循环自愈；如被系统"省电策略"杀掉，可将 Magisk 加入白名单。
4. **obsreve 耗时 1.4~3.7s**（uiautomator dump 固有开销），比原方案慢，但换来了稳定可用。
5. **中文输入依赖 helper（3072）**；若 helper 挂掉，set_value 自动退化为 ASCII `input text`。

---

## 九、回滚

```bash
# 只停服务（不卸载）
adb shell "su -c 'pkill -x vd_server_mi9; pkill -x app_process; pkill -x node'"

# 卸载模块
adb shell "su -c 'rm -rf /data/adb/modules/agent_mobile_use_mi9'"

# 彻底清理 chroot（可选，约 1.2GB）
adb shell "su -c 'rm -rf /data/local/ubuntu /data/local/tmp/agent'"
```

---

## 十、工作区文件索引

| 路径 | 说明 |
|---|---|
| `agent-mobile-use-main/` | 上游源码（已打补丁） |
| `agent-mobile-use-main/vd-tool-java/build_win.sh` | Windows 下 javac+d8 构建两个 dex |
| `agent-mobile-use-main/vd-tool-java/src/com/agent/DaemonMain.java` | 副屏/剪贴板/注入守护（已改造） |
| `agent-mobile-use-main/vd-tool-java/src/com/agent/ToolMain.java` | 无障碍工具（已加 API 28 回退，前台模式未使用） |
| `vd-server-mi9/main.go` | Android 9 兼容网关 |
| `module-mi9/` | Magisk 模块源目录 |
| `agent_mobile_use_mi9.zip` | 可刷入模块包 |
| `tools/zipmod/` | 生成正斜杠路径 zip 的小工具（Windows 打包必需） |
| `probe/` | 真机探针脚本与验证截图 |
