# 隐域 / Mount Namespace

<p align="center">
  <strong>Android Mount Namespace 存储隔离框架</strong><br>
  <sub>基于 Linux 内核 bind mount 与命名空间隔离技术，实现目录与文件的进程级隐藏</sub>
</p>

---

## 核心能力

在 Root 环境下，通过 Linux Mount Namespace 隔离技术，将指定路径从全局挂载视图中"摘除"——其他应用和文件管理器看到的是空目录或无法访问，而 logd 守护进程自身仍可正常访问。

不同于简单的 `chmod` 或 `.nomedia` 方案，本工具工作在 Linux 内核 VFS 层，对所有文件系统操作（`ls`、`stat`、`readdir`）均生效，且不修改任何实际数据。

## 技术架构

### 双层隔离模型

```
┌──────────────────────────────────────────────┐
│            Init Mount Namespace              │
│                                              │
│   App A  ──►  /data/adb/  (空目录)           │
│   App B  ──►  /data/adb/  (空目录)           │
│                                              │
│   logd (守护进程)                            │
│       └─ bind mount 空目录 → 目标路径         │
│       └─ 新建独立 namespace (恢复自身可见)     │
│       └─ 轮询控制文件, 收到命令自动 umount     │
└──────────────────────────────────────────────┘
```

### 子挂载穿透处理

对于 `/data/adb` 这类包含子挂载（如 KSU 的 `ksud` 文件系统挂载）的目录，直接 bind mount 父目录无法覆盖子挂载点。本工具会自动扫描子挂载并逐层覆盖，确保彻底隐藏。

### 文件控制通道

隐藏 `/data/adb` 等路径后，`su` 二进制可能随之隐藏，导致 App 无法再通过 su 控制守护进程。为此提供了**文件控制通道**：

- 守护进程每秒检查一次 App 私有目录下的 `logd.cmd` 文件
- App 只需在自己目录里写入命令文件（无需 su 权限）
- 支持命令：`unhide` / `stop` / `exit`（恢复并退出）、`ping`（探测存活）

确保即使 su 被隐藏，也能安全恢复，无需重启设备。

## 原生守护进程

| 属性 | 值 |
|------|-----|
| 二进制名称 | `logd` |
| 进程名 | `logd`（通过 `prctl(PR_SET_NAME)` 设置） |
| 架构 | ARM64 (aarch64) |
| 依赖 | 零外部依赖，仅依赖 bionic libc |
| 编译工具链 | Android NDK (clang) |
| 优化级别 | `-Os -s` (最小体积 + strip symbols) |
| 体积 | ~22KB |

守护进程以 `logd` 名称运行——这是 Android 系统自带的日志守护进程名，在 `ps` 输出中不引人注目。

### 运行模式

**正常模式** `logd <config_path>`

读取配置文件中的路径列表，进入 init mount namespace 执行 bind mount，创建独立 namespace 保持自身可见，轮询控制文件并响应信号，收到退出命令后重新进入 init namespace 执行 umount 并退出。

**清理模式** `logd --cleanup <config_path>`

进入 init mount namespace，umount 所有之前 bind mount 的路径。用于异常恢复（进程崩溃后残留的 bind mount）。

## 构建与编译

### 环境要求

- Android SDK Platform 35+
- Android NDK r27+ (用于编译原生守护进程)
- JDK 11+
- Gradle 8.x

### 编译原生守护进程

```bash
cd native
# 使用 NDK clang 编译
export NDK_PATH=/path/to/android-ndk
$NDK_PATH/toolchains/llvm/prebuilt/linux-x86_64/bin/aarch64-linux-android21-clang \
  -Os -s -Wall -D_GNU_SOURCE -o logd_arm64-v8a logd_src.c
```

将编译产物复制到 Android 项目的 assets 目录：

```bash
cp logd_arm64-v8a ../app/src/main/assets/logd_arm64-v8a
```

### 构建 APK

```bash
./gradlew assembleRelease
```

生成的 APK 位于 `app/build/outputs/apk/release/app-release.apk`。

## 项目结构

```
logd/
├── app/
│   ├── build.gradle                    # Android 模块构建配置
│   ├── proguard-rules.pro
│   └── src/main/
│       ├── AndroidManifest.xml
│       ├── assets/
│       │   └── logd_arm64-v8a          # 编译后的原生守护进程
│       ├── java/com/example/logd/
│       │   └── MainActivity.java       # 应用主界面与逻辑
│       └── res/
│           ├── drawable/               # 矢量图标
│           ├── mipmap-anydpi-v26/      # 自适应图标
│           └── values/                 # 主题与字符串
├── native/
│   ├── logd_src.c                      # 守护进程主源码（单文件）
│   ├── logd.h                          # 共享头文件
│   ├── logd_storage.c                  # 存储隔离变体
│   ├── main.c                          # 入口（多文件版本）
│   ├── namespace.c                     # 命名空间管理
│   ├── mount_simple.c                  # 普通目录 bind mount
│   ├── mount_android_data.c            # Android/data 隔离
│   ├── Makefile                        # NDK 交叉编译配置
│   └── Makefile_storage
├── gradle/
│   └── wrapper/
├── build.gradle                         # 根构建文件
├── settings.gradle
└── gradle.properties
```

## 运行环境

| 条件 | 要求 |
|------|------|
| 系统 | Android 8.0 (API 26) 及以上 |
| 架构 | ARM64 (arm64-v8a) |
| 内核 | Linux 4.x / 5.x / 6.x |
| 权限 | Root (Magisk / KernelSU / APatch) |

## 安全说明

- 不修改任何用户数据，仅在 VFS 层创建临时 bind mount
- 重启后所有 bind mount 自动消失（mount 表不持久化）
- 守护进程崩溃时残留的 bind mount 可通过清理模式或重启恢复
- 不使用 overlayfs，不修改文件系统结构
- 文件控制通道确保 su 被隐藏时仍可安全恢复

## License

MIT
