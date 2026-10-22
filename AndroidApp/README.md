# OpenBrowser Mobile · 安卓端

OpenBrowser 的手机独立客户端：在 Android 上管理多套互相隔离的浏览器环境，每个环境拥有独立的
Cookie / 缓存 / localStorage / IndexedDB，并可按环境覆盖 UA、平台、语言、时区、屏幕、WebGL、
Canvas / Audio 噪声、WebRTC 策略与代理。

> 与桌面端的关系：安卓端可按环境选择系统 WebView（Chromium）或随 APK 打包的 Mozilla GeckoView（Firefox）。
> 两种内核都在手机本地渲染，不依赖远程控制；WebView 支持现有代理和 JS 指纹注入，GeckoView 使用
> contextId 隔离 Cookie / 缓存 / localStorage，但暂不支持代理和 WebView 指纹注入。

## 功能

### 1.8.0：网站存储、密码库及自动双向同步

电脑端版本为 1.0.27，手机端为 1.8.0。两端入口仍是「电脑与手机同步」，在原有手动交换下面增加自动同步设置。

实现内容：

- 新增独立勾选的网站存储和 OpenBrowser 密码库同步；自动同步、手动加密文件及手动传输均使用这些勾选项。
- localStorage 按完整来源（协议、域名、端口）保存；IndexedDB 携带数据库版本、表、主键、索引、记录，并保留 Date、二进制、Blob/File、Map/Set、BigInt 等可移植类型。采集失败或不支持的类型不会被转换为空数据。
- 密码库按环境管理网站来源、用户名和密码，支持添加、修改、删除、复制；电脑可导入环境原有平台账号。安卓浏览器菜单可按当前来源手动选择账号填入，不自动提交。密码只进入加密本机存储和加密交换包，列表不显示密码；复制后 30 秒尝试清除剪贴板。
- 网站存储、密码库分别参与三方合并和冲突选择。新数据使用 v2 协议，读取兼容旧 v1；旧版客户端无法读取 v2，需两端一起更新，避免旧客户端忽略新字段后覆盖。
- 网站存储导入为一次性待应用状态。电脑在启动时恢复；安卓在首次打开相同来源前，用无网络的本地 HTML 先恢复，再打开网站。待应用数据不被后续旧快照覆盖，恢复失败保留快照并停止打开目标站点。
- WebDAV、S3 / MinIO 原生 AWS SigV4、对象存储预签名 URL、局域网临时交换、本地共享文件。
- 两端共享同一自动同步文件、空间标识和加密密码；自动文件与手动快照文件分开使用。
- 配置、Cookie 分开做三方合并：单端修改自动采用修改方；双方同时修改则保留冲突，不采用设备时间决定覆盖。冲突按钮仅适用于用户已查看的两份版本，版本再次变化后需重新处理。
- 后台定时任务、修改后的调度、离线失败重试、暂停、手动立即同步、非计费网络限制（Android）、最近 50 条同步记录、清除本机连接。
- Android 凭据、基线和记录使用 Keystore 加密；电脑使用系统密钥库 `safeStorage`，拒绝 Linux `basic_text` 明文后备方式。配置不可解密时停止自动同步，不用空配置覆盖损坏文件。
- Android 所选 WebView 环境切到后台时生成已知网址的 Cookie 快照；电脑使用已关闭且已开启云备份环境的快照。默认不传 Cookie，需单独开启。
- 已知 Cookie 的删除随快照合并处理，桌面导入为一次性待应用状态，已消费的导入不会因旧界面数据再次回放。
- 浏览器和同步导入通过跨进程文件租约协调；正在浏览或生成 Cookie 快照的环境不会被外部同步 / 清理覆盖。删 WebView 数据目录前停止仍持有数据库句柄的空闲浏览器进程；Gecko 清理保留待完成标记并跟踪原生异步结果。清理全部数据会暂停自动同步并清除本机同步凭据。
- 不传播环境删除；已经在本机移除的已同步环境不会在下一轮被自动恢复。首次自动接收陌生 ID 需勾选「自动接收空间中新环境」。

**配置一次后使用：**

1. 在 WebDAV 或对象存储准备一个新文件地址，例如 `https://dav.example.com/OpenBrowser/shared-vault.obsync`。目录 / Bucket 必须已存在。
2. 两端使用相同 URL、空间标识（例如 `personal`）及至少 12 字符的密码。电脑端在环境偏好开启「云备份」，手机选择要同步的环境；需要接收另一端新环境时勾选自动接收。
3. S3 原生签名填写完整对象 URL（不含查询参数）、Region、Access Key、Secret Key，可选 Session Token。R2 的 Region 可填 `auto`。密钥需要相应对象的读写及条件写入权限；不能读取不存在对象状态的权限配置会返回 HTTP 错误。
4. 勾选自动同步并保存，然后点「立即双向同步」。初次同 ID 内容不同会提示冲突，确认采用哪一端后才能继续。
5. Android 后台周期至少 15 分钟，受系统省电、网络和强制停止限制；修改后会安排一次任务。电脑需保持运行，失败按指数退避重试。运行中环境暂缓接收，请关闭环境后再次同步。
6. 不使用云存储时，在同一局域网，电脑先勾选自动同步、选择空间标识及密码，再开启局域网交换；手机粘贴临时地址并保存同样的空间设置。临时服务只在内存保留交换包，10 分钟到期；到期或电脑重启后需要重新开启并更新手机地址。
7. 本地共享文件通过文件选择器指定并授予持久读写权限。不支持定位 / 锁定的文档提供商会失败，不会绕过限制写入。通过其他工具镜像文件时，跨设备原子性取决于文件服务，应保留镜像工具生成的冲突副本。本地写入保留上一份密文供恢复。

**仍然适用的能力边界：**Cookie 是已知网站和路径的快照，不是浏览器数据库整库迁移；不支持分区 Cookie 或 Gecko Cookie / 网站存储交换。
网站存储只采集已打开页面的顶层来源；电脑运行中约每 15 秒及主动关闭前采集，安卓页面加载后和切到后台时采集。未打开的来源、第三方分区存储、sessionStorage、Service Worker / Cache Storage、扩展、历史和书签不在此次新增协议内。
IndexedDB 按来源内数据库逐项恢复，同数据库中的记录替换使用一个事务；失败时尝试回滚，回滚也失败会停止打开且保留待应用快照。已有数据库的版本或结构不同会拒绝恢复，需用户另行处理，不能冒充跨数据库原子事务；不自动删除快照中未出现的数据库 / 来源。自增计数器不保证与另一台设备完全一致。
单来源快照最多 2 MB、50 个数据库、10000 条记录，每环境最多 50 个来源和 6 MB 网站快照；循环引用、CryptoKey 等不可移植值会拒绝整份来源快照。密码库最多 500 条。系统 Chrome / Google 密码库、Passkey 与设备硬件密钥没有被导出。
不同内核的底层指纹与网站设备绑定机制不同，不能保证指纹输出和登录态完全相同。
公网以及带账号的 WebDAV 使用 HTTPS；局域网无账号交换可用 HTTP，包体仍然加密。交换文件上限 8 MB；桌面单环境 Cookie JSON 上限 500000 字符，超限拒绝导入，不截断成损坏 JSON。

**验证状态：**网站存储 / 密码库新增 17 项测试通过（IndexedDB 事务测试使用模拟器，并非真机）；此前同步引擎 13 项测试全部通过，包含双客户端实际本地文件读写 / 加密 / 双向更新 / 冲突解决、Cookie 一次性消费、容量保护、手动覆盖保护和 AWS 官方签名样本；
原交换测试 10 项通过、2 项网络监听测试因沙箱限制跳过。界面模块加载和原云备份安全检查通过。
安卓新增 4 项 v2 协议 / 跨端摘要样本测试，加上此前 5 项合并与签名测试，连同已有加密 / 代理测试交由 Android 构建流程执行；本环境缺少可用 JDK/ADB，安卓编译、Lint、真机和实际远端服务验证尚未完成。

参考：[Android 后台任务约束](https://developer.android.com/develop/background-work/background-tasks/persistent/getting-started/define-work)、
[AWS SigV4 官方样本](https://docs.aws.amazon.com/AmazonS3/latest/developerguide/sig-v4-header-based-auth.html)。

**可重复的电脑 ↔ 手机自检：**在电脑项目根目录运行 `python -m http.server 8765 --bind <电脑局域网IP> --directory Browserapp/sync-smoke`，两端都打开 `http://<同一个电脑局域网IP>:8765/`。
先在电脑写入测试值，关闭环境并同步；手机同步后打开同一来源，点「读取本机数据」，应同时显示相同 localStorage 值、IndexedDB 值以及 `dateType / binaryType = true`。
再从手机改值，切后台等待快照完成、关闭环境并同步；电脑接收后重新打开页面读取，验证反向传输。两边分别修改同一来源后应先提示冲突；密码库也可用测试账号分别验证添加、改密和删除，勿使用真实密码做演示。完成后关闭测试服务器。

Windows 完整检查并覆盖安装：`powershell -ExecutionPolicy Bypass -File .\build-install.ps1 -CheckDesktop`。
先运行桌面同步回归，再构建安卓、执行安卓测试与 Lint，通过后才保留数据安装。恢复本地上一份密文可在同步页面导出，导入前仍会预览；远端历史版本使用存储服务自身的版本功能。

### 1.6.0：电脑 / 手机加密交换

手机入口：**设置 → 电脑与手机同步**。电脑入口：**本地设置 → 电脑与手机同步**。
此版本提供用户主动发起的推送 / 拉取及导入预览，**不是自动后台双向同步**。

| 方式 | 使用方式 |
| --- | --- |
| 仅本地文件 | 导出 `.obsync`，通过 USB、共享文件夹或自行选择的文件同步工具传输，再在另一端导入。不要求云存储 |
| WebDAV | 填已存在目录下的完整文件 URL 和账号密码，上传本机快照或下载另一端快照 |
| 对象存储 | 填同一对象的 GET / PUT 预签名 URL，适用于支持条件写入和强 ETag 的服务；尚无内置 S3 Access Key 签名 / 管理 |
| 局域网直连 | 电脑开启临时交换，将显示的地址粘贴到手机「下载 URL」，手机选择局域网模式。可以下载电脑快照，也可上传手机快照，再在电脑确认导入 |

**第一次使用：**

1. 两端输入相同、至少 12 字符的同步密码。密码不保存，遗失后不能恢复交换文件。
2. 手机明确选择要导出的环境；电脑仅导出在环境偏好中开启「云备份」的环境，并要求先关闭这些环境。
3. 默认只新增不存在的 ID；重复导入不会复制同一环境。若要更新同 ID 环境，先关闭该环境，再勾选「替换同 ID 配置」，阅读预览后确认。不会同步删除操作。
4. WebDAV / 对象存储建议按设备分文件（如 `desktop.obsync` / `phone.obsync`）。上传会替换目标文件中的旧快照，不自动合并旧文件内容。条件写入用于检测上传期间的并发修改；服务器缺少强 ETag 时拒绝覆盖，需换新文件名。
5. 局域网交换仅绑定电脑的一个私网 IPv4 地址，临时地址含随机访问令牌，10 分钟后关闭。交换文件仅留在内存中，不落入远端服务器；电脑休眠、关闭或网络隔离时不可用。请勿转发临时地址。

交换范围是两端支持的共同参数：UA、平台、语言、时区、屏幕、DPR、CPU、内存、触点、WebGL、
Canvas / Audio 开关、WebRTC 策略、环境备注和代理。两端内核和噪声实现不同，**相同配置不代表网站看到的所有指纹完全相同**。
桌面端使用真实 CPU / 内存、屏蔽 Canvas 或静音 Audio 等当前无法完整映射的配置会拒绝导出。
书签、历史、标签、密码库、扩展数据、localStorage 和 IndexedDB 尚不通过此格式交换；原桌面 `.obpack` 备份功能仍独立保留。

**Cookie 为可选功能：**

- 手机先在对应 **WebView 环境**菜单选择「保存 Cookie 同步快照」，再在同步界面勾选 Cookie 导出。
- 使用 `CookieManagerCompat.getCookieInfo()` 读取已知访问网址与路径的 Cookie 属性，保留 Domain、Path、Secure、HttpOnly、SameSite 等。仅覆盖最近 200 个已知 URL，不是完整 Cookie 库枚举；分区 Cookie 不迁移。旧版 WebView 不支持时明确提示升级。
- 手机快照和待应用 Cookie 由 Android Keystore 密钥在本机加密保存；清空对应网站数据或永久删除环境时同时清理。
- 电脑 Cookie 来自已关闭环境的已有 Cookie 快照。Cookie 单独勾选导入；保留本地同 ID 环境时，其 Cookie 也保留不动。手机在下次打开相同环境的数据目录内应用待导入 Cookie，应用失败时不加载网页。
- Gecko 暂不支持 Cookie 交换；配置了代理的 Gecko 环境现在直接停止打开，避免意外直连。
- 网站可能依赖设备绑定、其他存储或额外验证，因此 Cookie 转移不保证登录态恢复。

交换包固定使用 PBKDF2-HMAC-SHA256（210000 次）及 AES-256-GCM，禁止明文交换，上限 8 MB；不跟随远端重定向。
公网 URL 要求 HTTPS，私网 IPv4 可通过 HTTP 传输已加密包。远端密码、预签名 URL 和同步密码不持久保存。

验证：`cd Browserapp && npm run selftest:portable`。当前环境 10 项通过、2 项监听端口测试因沙箱限制跳过；
安卓侧新增 3 个加密测试（含桌面生成的中文密码样本）。Android 构建、单元测试、Lint、网络真连及真机互通仍待执行，不能视为已验收功能。

| 模块 | 说明 |
| --- | --- |
| 环境隔离 | WebView 使用独立数据目录后缀；GeckoView 使用独立 contextId；两种模式的 Cookie / 缓存 / localStorage / IndexedDB 均按环境隔离 |
| 设备预设 | Pixel 8、Galaxy S24、小米 14、iPhone 15 Pro、iPad Air、Windows / macOS Chrome |
| 指纹参数 | UA、`navigator.platform`、语言、时区、屏幕尺寸、DPR、CPU 核心、内存、触摸点、WebGL Vendor/Renderer |
| 噪声 | Canvas（同环境结果稳定）、Audio（AudioBuffer / AnalyserNode） |
| 随机指纹 | 环境菜单或编辑页一键随机生成一套自洽的设备指纹（UA / 平台 / 屏幕 / DPR / WebGL / CPU / 内存），执行前弹框确认；语言、时区与代理保持不变。机型库含 33 款安卓、12 款 iOS 及 Windows / macOS 屏幕与显卡组合，Chrome / iOS 版本独立随机，卡片上会显示抽到的机型名 |
| WebRTC | 屏蔽 host/srflx 候选、透传真实地址、或替换为指定 IP |
| 代理 | 直连 / HTTP / HTTPS / SOCKS5，支持用户名密码；带认证与 SOCKS5 时通过本机转发器接入 |
| 出口检测 | 编辑页一键检测代理出口 IP |
| 数据管理 | 单环境清空网站数据、全部环境清空、回收站（30 天可恢复登录态/缓存）、导入 / 导出环境配置（JSON） |
| 桌面快捷方式 | 环境菜单「添加到桌面」，一键把某个环境固定到桌面（兼容不支持官方接口的国产桌面）；同一环境只保留一个图标，删除环境时尝试自动撤回 |
| 环境检测 | 内置检测页：公网 IP / 归属地 / 运营商、WebRTC 泄露、时区 / 语言 / UA 一致性、WebGL / Canvas / 硬件指纹，并给出自洽评分 |
| 浏览器 | 单行顶栏（返回 / 地址栏 / 标签数 / 菜单），地址栏可设为顶部或底部；一个环境内支持多标签（上限 8），点地址栏右侧的数字打开手机 Chrome 风格的全屏标签网格；历史记录、书签、页内查找、分享链接、桌面版网站、在外部浏览器打开；新标签页是带搜索框 / 常用网站 / 最近访问的起始页；下载、文件上传、外链跳转、视频全屏 |
| 外观 | 参考 DroidDeck 的深色卡片式界面：渐变背景、细描边卡片、环境头像、状态胶囊、按压缩放、列表错峰入场、页面滑动过渡；支持跟随系统 / 浅色 / 深色，中英文界面 |

## 构建

需要 **JDK 17** 和 **Android SDK 34**（`platforms;android-34`、`build-tools;34.0.0`）。

```bash
cd AndroidApp
# 方式一：Android Studio 直接打开本目录，等待 Gradle 同步后运行
# 方式二：命令行
export JAVA_HOME=/path/to/jdk17          # Windows: set JAVA_HOME=...
export ANDROID_HOME=/path/to/android-sdk
./gradlew assembleDebug                  # 产物：app/build/outputs/apk/debug/
./gradlew assembleRelease                # 未签名 release 包
```

Windows 使用 `gradlew.bat`。

Windows 也可以在本目录运行以下脚本，一次完成构建、代理回归测试、Lint、覆盖安装和启动：

```powershell
powershell -ExecutionPolicy Bypass -File .\build-install.ps1
# 只构建检查，不安装：追加 -BuildOnly；连接多台手机时追加 -Serial 设备序列号。
```

脚本优先使用 `JAVA_HOME`、本地 SDK 配置和 Android 环境变量，也兼容此前放在
`%LOCALAPPDATA%\Temp\opencode\android-build` 的工具链。手机需开启 USB 调试并授权此电脑。
检查失败会停止安装；使用 `adb install -r` 保留已有数据，不会卸载应用或自动降级。
若签名不一致，需先确认原安装包的签名和备份方式，脚本不会清空原应用来绕过错误。

### 1.5.1 稳定性修订

- 环境配置读写增加进程内共享锁、跨进程文件锁和 `AtomicFile` 提交，减少主界面与浏览器同时操作时丢失更新的风险。
- 代理覆盖等待系统完成回调后才加载网页；无效配置、不支持或设置失败时停止打开环境。
- HTTPS 上游代理使用 TLS 并校验证书和主机名；拒绝 SOCKS5 选择未提供的认证方式；修正 IPv6 CONNECT 地址格式。
- 本机代理限制连接处理线程，停止时关闭活动连接；已建立隧道不再受读取请求头的 30 秒超时影响。
- WebView 隔离目录设置失败时停止打开，不再退回共享目录。
- JSON 导入支持对象清单和顶层数组；导入 ID 校验同时考虑现有环境、回收站和数据目录后缀冲突。
- 系统 DownloadManager 不继承 WebView 代理，因此暂时阻止代理环境的下载并说明原因；直连环境仍可下载。

新增 5 个本地假代理单元测试，覆盖无效代理、IPv6 CONNECT、407 拒绝后的连接关闭、SOCKS5 认证方式和 HTTPS TLS 握手。
本次修改所在环境无法运行 Windows JDK/ADB，且缺少 Linux JDK，因此这些测试、APK 构建和真机安装尚未执行。

### 产品化待办与真机验收

当前仍属于开发版，以下内容完成前不能视为稳定产品：

| 优先级 | 缺口 | 验收方向 |
| --- | --- | --- |
| P0 | 构建与真机回归 | 单元测试、Lint 通过；覆盖安装后环境、书签和登录态保留；无启动崩溃 |
| P0 | 隔离与代理真实验证 | A/B 环境 Cookie、localStorage、IndexedDB 互不串用；代理断开时不直连；分别验证 HTTP/HTTPS/SOCKS5 |
| P0 | 正在运行的环境清理 | 清理前停止对应浏览器；避免在打开的 WebView 数据库上直接删除目录 |
| P1 | 会话恢复与崩溃恢复 | 系统回收、渲染进程退出后恢复标签；记录脱敏错误，提供重试入口 |
| P1 | 代理下载 | 通过相同上游下载，支持取消、进度和失败重试；当前明确阻止系统直连下载 |
| P1 | 双内核能力与内核维护 | 真机验证 Gecko 的登录、文件、权限和多标签；评估内核更新，准确显示能力限制 |
| P1 | 导入、备份与磁盘故障 | 大文件限制、损坏数据恢复、备份凭据保护和磁盘不足提示；当前导出仅含环境配置 |
| P2 | 交互与可访问性 | 大字体、键盘遮挡、深浅色、空列表、离线和慢网络状态 |

安装后至少验证：创建两个环境并分别登录、切换环境、开关多标签、代理失败、上传文件、直连下载、
删除后恢复环境，以及切到后台再返回。不要使用“清空全部数据”来验证覆盖安装。

如果 `local.properties` 不存在，Gradle 会读环境变量 `ANDROID_HOME` / `ANDROID_SDK_ROOT`；
也可以手写：

```properties
sdk.dir=C:\\Users\\you\\AppData\\Local\\Android\\Sdk
```

安装到手机：

```bash
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

## 实现要点

```
AndroidApp/
├── app/src/main/java/com/openbrowser/mobile/
│   ├── data/            环境模型、设备预设、profiles.json 存储
│   ├── proxy/           上游拨号（HTTP CONNECT / SOCKS5）、本机转发、出口检测
│   ├── web/             指纹脚本合成、数据目录后缀、进程重启、数据清理
│   └── ui/              环境列表、环境编辑、浏览器、设置
└── app/src/main/assets/fingerprint.js   注入脚本（document-start）
```

### 1. 存储隔离：按内核隔离环境数据

`WebView.setDataDirectorySuffix()` 每个进程只能调用一次，且必须在创建 WebView 之前调用。GeckoView 使用
`GeckoSessionSettings.Builder.contextId()`，同一个 contextId 的标签共享登录态，不同环境完全分开。
因此：

- `BrowserActivity` 声明在独立的 `:browser` 进程中，GeckoView 浏览器声明在独立的 `:gecko` 进程中；
- 打开环境 A 时设置后缀 `p_<id>`，WebView 的数据落在 `app_webview/p_<id>`；
- 再打开环境 B 时后缀不同 → 通过 `AlarmManager` + 广播在杀掉 `:browser` 进程后重新拉起界面，
  新进程再设置 B 的后缀。

这样 Cookie、localStorage、IndexedDB、Cache 天然隔离，且同一环境再次打开时数据仍在。

### 2. 指纹注入：document-start

优先使用 `WebViewCompat.addDocumentStartJavaScript`（需要较新的 System WebView），
在不支持的设备上退回 `onPageStarted` + `evaluateJavascript` 注入。

注入项与配置见 `assets/fingerprint.js`，配置由 `Profile.fingerprintJson()` 生成：
UA / appVersion / platform / vendor / languages / hardwareConcurrency / deviceMemory /
maxTouchPoints / webdriver / userAgentData（Client Hints）/ screen / devicePixelRatio /
outerWidth / 时区（`Intl.DateTimeFormat` + Date 本地时间平移）/ WebGL 厂商渲染器 /
Canvas 噪声 / Audio 噪声 / WebRTC 候选过滤 / `window.chrome` 兜底。

Canvas 噪声使用环境 id 作为种子，**同一环境每次生成的图片一致**，避免“同环境两次访问指纹不同”。

### 3. 代理

- 无认证的 HTTP 代理：直接交给 `androidx.webkit.ProxyController`；
- HTTP/HTTPS 带认证、SOCKS5：先启动本机 `LocalProxyServer`（监听 `127.0.0.1:随机端口`），
  由它完成 CONNECT 隧道或 SOCKS5 握手（含 RFC1929 认证），WebView 只连本机转发器；
- 直连：清除代理覆盖。

`ProxyController` 是应用级设置，因此安卓端**同一时刻只运行一个环境**（符合手机使用习惯）。

### 4. 数据清理

删除 `app_webview/p_<id>`（兼容 `app_webview_p_<id>`）与对应的 WebView 缓存目录；
在浏览器内清空时还会调用 `CookieManager` / `WebStorage` / `WebView.clearCache`。

删除环境或清空全部环境时，会同步尝试撤回该环境的桌面快捷方式：
快捷方式以「长驻」方式创建，删除时调用 `ShortcutManagerCompat.removeLongLivedShortcuts`，
并补发旧版 `UNINSTALL_SHORTCUT` 广播；部分桌面不允许应用删除图标，此时点图标会提示环境已删除。

删除环境默认先进入回收站，保留 30 天。回收站中的环境配置、Cookie、缓存、历史和书签仍可恢复；
点击恢复会复用原来的环境 ID，因此原有登录态继续有效。点击永久删除或到期清理时，才会删除网站数据、
历史、书签、下载记录和桌面快捷方式。

永久删除的应用侧数据由 `ProfileDataCleaner` 统一处理，避免不同入口的清理范围不一致；GeckoView 的 context 数据
会通过独立的 `:gecko` 清理 Activity 调用 `StorageController.clearDataForSessionContext()`。

### 5. 内置环境检测页

启动页留空（新建环境的默认值）时，浏览器打开 `assets/check.html`：

- 出口 IP / 归属地 / 运营商 / IP 时区（依次尝试 `ipwho.is`、`ipinfo.io`、`ipify`）；
- WebRTC 是否泄露 ICE 候选地址；
- 时区 ↔ IP、语言 ↔ 地区、UA ↔ Platform 一致性；
- 已生效的 UA / Platform / 语言 / 时区 / 屏幕 / DPR / CPU / 内存 / 触点 / WebGL / Canvas 指纹。

页面顶部给出 0–100 的自洽评分。页面通过 `loadDataWithBaseURL` 注入，不依赖 `file://` 访问，
因此可以保持 `allowFileAccess = false`。

### 6. 随机指纹

`FingerprintGenerator` 从一组真实设备组合中抽取一套自洽的 UA / 平台 / 屏幕 / DPR / 触点 /
WebGL / CPU / 内存，**不改语言、时区与代理**（这三项必须和出口 IP 对齐）。
入口有两个：环境卡片的「更多 → 重新生成指纹」，以及编辑页「设备指纹」分区右上角的「随机生成」；
两者都会先弹出确认框。

### 7. 单窗口多标签

`BrowserActivity` 使用 `singleTask`，同一环境反复打开只会复用一个实例；
一个环境内部可以开多个标签页（上限 8 个），所有标签共用该环境的 WebView 数据目录，
所以登录态在标签之间共享，但不会串到其他环境。

- 顶部不放横向标签条：地址栏右侧是一个标签数方块，点开手机 Chrome 风格的全屏标签网格；
- 「⋮ → 新建标签页」或网格底部的「新建标签页」新增，上限 8 个；
- 网格里点卡片切换、点 × 关闭；返回键在无历史时先关当前标签、再退出；
- 网格弹窗淡入上浮、卡片错峰入场并有按压缩放；
- 页面的 `window.open` / `target="_blank"` 会在当前环境内新开一个标签（达到上限时忽略）。

### 8. 历史记录 / 书签 / 起始页

历史与书签都按环境隔离存放（`HistoryStore` / `BookmarkStore`，SharedPreferences + JSON）：

- `⋮ → 历史记录`：全屏列表，显示标题 / 网址 / 时间；点条目在新标签打开，× 删除，右上角可清空；
- `⋮ → 收藏此页`：已收藏时菜单项变为「取消收藏」；`⋮ → 书签` 查看全部；
- 新标签页打开内置起始页（`assets/start.html`）：搜索框、常用网站（书签）、最近访问，数据由原生侧注入，不联网、不上传；
- 历史最多 400 条、书签最多 300 条；同一 URL 只保留一条并更新时间。

环境配置文件带有 schema version。读取旧版 `profiles.json` 或桌面端导出文件时会补齐缺失的内核、
机型和 WebRTC 字段，后续增加环境配置可以通过迁移逻辑保持旧数据可用。

### 9. 桌面版网站

`⋮ → 桌面版网站` 会把当前标签的 UA 换成 Windows Chrome 并关闭「按宽度缩放」，刷新后生效；
该开关只作用于当前标签，不会修改环境本身的指纹配置。

### 10. 页内查找 / 下载 / 视频全屏

- `⋮ → 在页面中查找`：顶部出现查找栏，输入即高亮并显示「第 n / 共 m 条」，可上下跳转，返回键或 × 关闭；
- `⋮ → 下载`：按环境列出本环境发起过的下载任务，显示状态与时间；点已完成条目直接打开文件，× 只移除记录、不删除文件；
- 视频全屏：`WebChromeClient.onShowCustomView` 把播放器铺满整屏并保持亮屏，返回键退出；
- 历史 / 书签列表顶部带搜索框，可按标题或网址过滤；
- `target="_blank"` / `window.open` 转为当前环境内的新标签页（`onCreateWindow` + `WebViewTransport`）。

## 与桌面端的功能差异

| 能力 | 桌面端 | 安卓端 |
| --- | --- | --- |
| 内核 | 内置 / 可下载 Chromium | 系统 WebView 或 GeckoView（按环境选择） |
| 指纹强度 | 原生层 + JS 层 | WebView 为 JS 层；GeckoView 暂无注入 |
| 多环境并行 | 支持 | 同一时刻一个（ProxyController 为应用级） |
| 窗口同步 / RPA / 扩展 | 支持 | 未实现 |
| Local API / MCP | 支持 | 未实现 |
| 备份 | 本地 / WebDAV / GitHub | 导入导出 JSON |

## 注意事项

- 最低 Android 9（API 28），`targetSdk 34`；请保持 Android System WebView 为较新版本。
- 编辑页修改代理后，需要重新打开环境才会生效（浏览器启动时应用代理）。
- 受 Google 政策限制，在系统 WebView 中登录 Google 账号通常会被拒绝。可以先打开 `⋮ → 桌面版网站` 再试；GeckoView 是 Firefox 内核，登录兼容性通常更好，但仍需以目标站点实际策略为准。
- 清空数据时如果该环境正在浏览器中打开，建议先关闭浏览器。
- 本项目仅供学习与合规的多账号环境管理使用；请阅读仓库根目录的
  [DISCLAIMER.md](../DISCLAIMER.md)，并遵守目标网站条款与当地法律法规。
