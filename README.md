# MCLQE Launcher

一个基于 **Java 21 + JavaFX 21 + Maven** 的 Minecraft 启动器（版本 1.0.0）。全部源代码为独立编写，仅在功能设计与技术方案层面参考社区公开启动器与 Mojang 官方文档，详见「九、来源与致谢」。

工程已在本机实际执行 `mvnw.cmd -B -DskipTests package` 构建通过（`BUILD SUCCESS`），产物为 `target/mclqe-launcher.jar`。

---

## 一、功能覆盖

| 需求项 | 实现说明 | 主要实现类 |
|---|---|---|
| ① 版本清单解析 | 拉取 `version_manifest_v2.json`，解析版本 JSON，支持 `inheritsFrom` 父版本继承合并（libraries / arguments / assetIndex / mainClass / downloads 合并与去重） | `VersionManager`、`VersionManifest`、`VersionMeta` |
| ② 多线程下载与校验 | 线程池并发下载 client.jar / libraries / natives / assets，SHA1 校验、大小校验、Range 断点续传、最多 3 次退避重试；已存在且校验通过的文件直接跳过 | `DownloadManager`、`HttpUtils`、`LibraryManager`、`AssetManager` |
| ③ Java 运行时检测 | 扫描 PATH 及各厂商安装目录（Oracle / Eclipse Adoptium / Zulu / Microsoft / Liberica / Temurin / GraalVM 等）与 `JAVA_HOME`，解析 `java -version` 得到主版本号，按版本号优选（含 JDK21） | `JavaDetector` |
| ④ 规则过滤与命令拼接 | 按 `rules`（os.name / os.arch / os.version / features）判定 library 与 argument 是否放行；解析 natives classifier 并解压（含 ZipSlip 防护、`extract.exclude` 支持）；拼接完整 JVM 与游戏参数 | `PlatformRules`、`NativeExtractor`、`CommandBuilder`、`LaunchProfile` |
| ⑤ 离线账户登录 | 官方离线 UUID 算法 `UUID.nameUUIDFromBytes("OfflinePlayer:<name>")`，生成 username / uuid / accessToken / userType 参数 | `OfflineAccount` |
| ⑥ JavaFX 图形界面 | 版本列表（含类型标注与"最新版本"置顶）、游戏目录选择、Java 运行时下拉选择、最大内存设置、离线用户名、启动/停止按钮、进度条、实时日志输出 | `LauncherApp`、`app.css` |
| ⑦ 配置持久化 | 版本、游戏目录、Java 路径、内存、窗口尺寸、用户名等以 JSON 持久化到 `%USERPROFILE%\.mclqe\config.json` | `LauncherConfig`、`ConfigManager` |

---

## 二、环境要求

- **JDK 21 或更高**（工程以 `maven.compiler.release=21` 编译；已在 JDK 26 环境验证通过）
- **Maven 3.8+**（可选用工程自带的 Maven Wrapper，无需预装 Maven）
- 可访问 `https://piston-meta.mojang.com`、`https://resources.download.minecraft.net`、`https://repo.maven.apache.org` 的网络环境
- Windows / Linux / macOS 均可，界面依赖 JavaFX（Maven 会按操作系统自动拉取对应 classifier）

---

## 三、编译与运行

### 3.1 Windows（PowerShell / CMD）

```bat
cd D:\MCLQE

:: 首次运行会自动下载 maven-wrapper.jar 与 Maven 3.9.9 发行版
mvnw.cmd clean package
```

### 3.2 Linux / macOS

```sh
cd /path/to/MCLQE
chmod +x mvnw
./mvnw clean package
```

### 3.3 启动图形界面（推荐，自动装配 JavaFX 模块路径）

```bat
mvnw.cmd javafx:run
```

### 3.4 已安装 Maven 时的等价命令

```bat
mvn clean package
mvn javafx:run
```

### 3.5 构建产物

| 产物 | 路径 |
|---|---|
| 可执行 jar（含 Main-Class 清单） | `D:\MCLQE\target\mclqe-launcher.jar` |
| 编译输出类目录 | `D:\MCLQE\target\classes` |

> 注：`mclqe-launcher.jar` 未内嵌 JavaFX 运行时。直接 `java -jar` 需要自行提供 JavaFX 模块，推荐统一使用 `mvnw.cmd javafx:run` 启动。

---

## 四、使用步骤

1. 启动后，程序自动读取（或创建）配置 `%USERPROFILE%\.mclqe\config.json`。
2. 首次启动会自动拉取 `version_manifest_v2.json` 并填充版本下拉列表；点击「刷新版本」可强制重新拉取。
3. 在「游戏目录」中选择游戏数据目录。首次启动默认创在**启动器程序所在目录下的 `MCLQE` 子文件夹**（如 `D:\MCLQE\MCLQE`，与启动器放在一起便于整体拷贝）；若程序目录不可写（如安装在 `C:\Program Files`），自动回退到 `%USERPROFILE%\MCLQE`。已存在的配置不会被改写。
4. 在「Java 运行时」中选择自动检测到的 JDK（默认优选最高主版本），或点击「浏览」手动指定 `javaw.exe` / `java.exe`。
5. 设置「最大内存」与离线用户名。
6. 点击「启动游戏」：程序依次准备目录 → 下载并校验 client.jar / libraries / assets / natives → 解压 natives → 拼接参数 → 启动进程，日志实时输出到界面。
7. 「停止游戏」可强制结束游戏进程；日志区域最多保留 20 万字符，超出后自动裁剪。

---

## 五、工程结构

```
D:\MCLQE
├── pom.xml                        # Maven 构建配置（Java 21 / JavaFX 21.0.2 / Gson 2.11.0）
├── mvnw                           # Maven Wrapper（Linux / macOS）
├── mvnw.cmd                       # Maven Wrapper（Windows）
├── .gitignore
├── .mvn\wrapper\maven-wrapper.properties
├── README.md
└── src\main
    ├── java\com\mclqe\launcher
    │   ├── Launcher.java              # 程序入口（Application.launch）
    │   ├── auth\OfflineAccount.java   # 离线账户与 UUID 算法
    │   ├── config\LauncherConfig.java # 配置数据模型
    │   ├── config\ConfigManager.java  # 配置 JSON 读写
    │   ├── model\                     # 官方 JSON 结构映射
    │   │   ├── DownloadInfo.java
    │   │   ├── AssetIndex.java
    │   │   ├── AssetObjects.java
    │   │   ├── Rule.java
    │   │   ├── Library.java
    │   │   ├── Arguments.java
    │   │   ├── VersionMeta.java
    │   │   └── VersionManifest.java
    │   ├── net\HttpUtils.java         # HTTP 文本/文件下载、SHA1、断点续传、重试
    │   ├── net\DownloadManager.java   # 多线程下载调度与进度回调
    │   ├── game\GamePaths.java        # .minecraft 目录布局
    │   ├── game\PlatformRules.java    # rules 判定 / natives classifier / OS 探测
    │   ├── game\VersionManager.java   # 版本清单与版本 JSON（含继承合并）
    │   ├── game\LibraryManager.java   # 依赖库过滤、下载任务、classpath 生成
    │   ├── game\AssetManager.java     # 资源索引与 objects 下载任务
    │   ├── game\NativeExtractor.java  # natives 解压（ZipSlip 防护）
    │   ├── game\JavaDetector.java     # 本机 Java 检测与版本解析
    │   ├── game\LaunchProfile.java    # 占位符上下文
    │   ├── game\CommandBuilder.java   # JVM / 游戏参数拼接
    │   ├── game\GameLauncher.java     # 启动编排与进程日志转发
    │   └── ui\LauncherApp.java        # JavaFX 主界面
    └── resources\app.css              # 界面样式
```

---

## 六、启动流程（源码级）

```
LauncherApp（UI 线程）
  └─ GameLauncher.launch()
       ├─ 1. GamePaths           校验 / 创建 .minecraft 目录结构
       ├─ 2. VersionManager      解析目标版本 JSON（含父版本合并）
       ├─ 3. LibraryManager      rules 过滤 → 收集下载任务 → 生成 classpath → 收集 natives
       ├─ 4. AssetManager        加载 assetIndex → 生成 objects 下载任务
       ├─ 5. DownloadManager     线程池并发下载（SHA1 校验 + 断点续传）
       ├─ 6. NativeExtractor     解压 natives 到 versions/<id>/natives
       ├─ 7. CommandBuilder      替换 ${...} 占位符 → 拼装 JVM 参数 + 游戏参数
       └─ 8. ProcessBuilder      启动游戏进程，stdout/stderr 转发到界面日志
```

### 关键实现细节

- **版本继承**：`VersionMeta.inheritsFrom` 逐级向上合并，子版本覆盖父版本的 `mainClass` / `assets` / `assetIndex`，`libraries` 合并去重，`arguments` 以父版本在前、子版本在后拼接。
- **rules 语义**：按顺序遍历 rules，命中者覆盖 `allowed` 状态，最终值即判定结果；rules 为空视为放行。
- **占位符**：支持 `${auth_player_name}`、`${version_name}`、`${game_directory}`、`${assets_root}`、`${assets_index_name}`、`${auth_uuid}`、`${auth_access_token}`、`${user_type}`、`${natives_directory}`、`${launcher_name}`、`${launcher_version}`、`${classpath}`、`${resolution_width}`、`${resolution_height}` 等。
- **下载校验**：SHA1 不匹配或大小不符时删除重下，最多重试 3 次；服务端支持 `Range` 时使用 206 续传，否则整文件重写。
- **配置容错**：配置文件缺失或 JSON 损坏时静默回退到默认配置，不阻断启动。
- **界面线程安全**：所有后台回调均通过 `Platform.runLater` 切回 UI 线程。

---

## 七、已知限制

- 微软登录已实现 OAuth 2.0 设备码流程，但账号令牌目前以 JSON 明文存放于本地配置目录，尚未接入 Windows DPAPI 系统级加密，公用电脑请谨慎使用。
- 加载器安装支持 Fabric / Quilt / Forge / NeoForge；其中 NeoForge 仅自 MC 1.20.2 起提供，更早版本请使用 Forge。
- 未处理 `assets` 的 virtual / map_to_resources 旧版资源目录映射（1.6 以前版本）。
- 未内置下载源镜像切换（默认使用 Mojang 官方、Maven Central 与各加载器官方源）。

---

## 八、第三方组件与依赖

| 组件 | 版本 | 许可证 | 用途 |
|---|---|---|---|
| OpenJFX（JavaFX） | 21.0.2 | GPL-2.0 with Classpath Exception | 图形界面 |
| Gson | 2.11.0 | Apache-2.0 | JSON 解析 |
| Maven Wrapper | 3.9.x | Apache-2.0 | 构建引导 |
| OpenJDK Runtime（绿色包含 `runtime/`） | 21+ | GPL-2.0 with Classpath Exception | 随包运行的 Java 运行时 |
| Mojang 版本清单 / 资源 API | — | 公开接口与文档 | 版本、库、资源下载 |
| Modrinth API | v2 | Modrinth API 使用条款 | 模组搜索与下载 |
| Fabric / Quilt / Forge / NeoForge 元数据 | — | 各自许可 | 加载器版本与安装器 |

---

## 九、来源与致谢

本项目所有 Java 源代码均由本项目作者独立编写，仓库中**不包含**下列任何项目的源代码副本、代码文件或二进制构件，也未复制其类名体系与实现细节。参考范围仅限于公开可见的功能设计、交互形式与官方技术文档。

| 参考对象 | 许可证 | 参考内容 |
|---|---|---|
| HMCL（Hello Minecraft! Launcher） | GPL-3.0 | 启动流程分层（准备目录 → 解析版本 → 下载校验 → 拼参 → 起进程）、断点续传与校验思路、界面信息组织方式 |
| PCL2（Plain Craft Launcher 2） | GPL-3.0 | 加载器安装的交互设计、错误提示的表达方式 |
| Prism Launcher / MultiMC | GPL-3.0 | 实例目录组织与多实例管理思路 |
| Mojang 官方启动器与官方文档 | 专有软件 / 公开文档 | 版本 JSON 结构、`rules` 语义、natives 与 assets 索引规范 |
| Fabric / Quilt / Forge / NeoForge 官方文档 | 各自许可 | 加载器安装流程与参数约定 |

上述项目名称与商标归各自权利人所有。本项目未声称与其存在任何隶属、赞助或合作关系。若权利人认为存在不当引用，请通过仓库 Issue 联系，将在核实后立即调整或移除。

---

## 十、免责声明：与网易、微软均无关联

1. 本项目为个人独立、非商业的开源学习与研究项目，**不是** Minecraft 官方产品，也未获得 Mojang Studios 或 Microsoft 的任何授权、赞助、认证或背书。
2. 本项目**与网易（Netease）及其关联公司没有任何关系**：不是网易代理的「我的世界 / Minecraft 中国版」相关产品，未使用其任何代码、客户端、资源、接口或服务，也未与其存在任何授权、合作、代理或隶属关系。本启动器不接入中国版账号体系，也不针对中国版做任何适配。
3. 「微软登录」仅调用微软公开的 OAuth 2.0 设备码授权接口完成正版账号身份验证（与官方启动器使用的公开流程同类），该实现**不代表**与 Microsoft 存在合作、授权或关联。
4. 「Minecraft」「我的世界」及相关名称、图标为 Mojang Studios / Microsoft 的商标，本项目仅在描述性意义上使用。
5. 本项目**不包含**任何游戏本体、资源文件或版权素材；游戏内容由用户使用自己合法持有的账号，从 Mojang 官方服务器下载，版权归 Mojang Studios 所有。
6. 使用者应自行遵守 Minecraft 最终用户许可协议（EULA）及所在地区法律法规；因使用本项目产生的一切后果由使用者自行承担。

---

## 十一、许可证

本项目源代码采用 GNU General Public License v3.0（GPL-3.0），全文见仓库根目录的 [`LICENSE`](LICENSE) 文件。

- Copyright (C) 2026 yezi-fak
- 你可以自由使用、修改、分发本项目；分发衍生作品时必须同样以 GPL-3.0 开源，并提供完整对应源码。
- 本项目不提供任何担保，详见 GPL-3.0 第 15、16 条。
- 选择 GPL-3.0 的原因：本项目在功能设计与技术方案上参考了同以 GPL-3.0 发布的 HMCL、PCL2、Prism Launcher 等开源启动器（见第九节），采用相同协议可保证生态兼容、避免许可证冲突；仓库中不包含上述项目的任何源代码。

第三方依赖与运行时遵循各自的许可证：JavaFX（GPL-2.0 with Classpath Exception）、Gson（Apache-2.0）、Maven Wrapper（Apache-2.0）、OpenJDK Runtime（GPL-2.0 with Classpath Exception），均与 GPL-3.0 兼容。

如需在非 GPL 条款下使用本项目代码，请通过仓库 Issue 联系作者获取单独授权。
