# 隐域 / Mount Namespace

<p align="center">
  <strong>Android 16 Mount Namespace 存储隔离框架</strong><br>
  <sub>基于 Linux 内核 bind mount 与命名空间隔离技术，实现应用数据目录的进程级隐藏</sub>
</p>

---

## 核心能力

在 Root 环境下，通过 Linux Mount Namespace 隔离技术，将指定应用的 `/storage/emulated/0/Android/data/<pkg>` 目录从全局挂载视图中"摘除"——其他应用和文件管理器看到的是空目录，而本应用自身仍可正常访问。效果上等同于"临时卸载应用但保留数据，且仅本应用可访问"。

不同于简单的 `chmod` 或 `.nomedia` 方案，GuardianShield 工作在 Linux 内核 VFS 层，对所有文件系统操作（`ls`、`stat`、`readdir`）均生效，且不修改任何实际数据。

## 技术架构

### 双层隔离模型

```
┌──────────────────────────────────────────────┐
│            Init Mount Namespace              │
│                                              │
│   App A  ──►  /storage/emulated/0/Android   │
│                  /data/com.target  (空)      │
│                                              │
│   App B  ──►  /storage/emulated/0/Android   │
│                  /data/com.target  (空)      │
│                                              │
│   GuardianShield (logd)                     │
│       └─ bind mount 空目录 → 目标路径         │
│       └─ 新建独立 namespace (恢复可见)       │
│       └─ 监听信号, 退出时自动 umount          │
└──────────────────────────────────────────────┘
```

### FUSE 双路径策略

Android 10+ 使用 FUSE 守护进程代理 `/storage/emulated` 的访问。直接 bind mount FUSE 路径无法对其他应用生效，因为 FUSE 守护进程会在内核态重定向到真实媒体路径。

GuardianShield 对 `Android/data` 和 `Android/obb` 路径采用双保险策略：

| 路径类型 | FUSE 路径 | 媒体路径 | 说明 |
|----------|-----------|----------|------|
| Android/data | `/storage/emulated/0/Android/data/pkg` | `/data/media/0/Android/data/pkg` | 同时 bind mount 两条路径 |
| /storage 其他 | `/storage/emulated/0/xxx` | — | FUSE shared 传播正常，直接 bind |
| 非 /storage | `/data/xxx` | — | 本地文件系统，无传播问题 |

### 属性同步

挂载空目录前，将空目录的 `uid`、`gid`、`mode`、`atime`、`mtime` 同步为目标目录的值。挂载完成后不再修改目标，避免触发 audit 日志或 SELinux 拒绝。

## 原生守护进程

| 属性 | 值 |
|------|-----|
| 二进制名称 | `logd` |
| 进程名 | `logd`（通过 `prctl(PR_SET_NAME)` 设置） |
| 架构 | ARM64 (aarch64) |
| 依赖 | 零外部依赖，仅依赖 Linux glibc 头文件 |
| 编译工具链 | Android NDK r26 (clang 17) |
| 优化级别 | `-O2 -s` (strip symbols) |

守护进程以 `logd` 名称运行——这是 Android 系统自带的日志守护进程名，在 `ps` 输出中不引人注目。

### 运行模式

**正常模式** `logd <config_path>`

读取配置文件中的路径列表，进入 init mount namespace 执行 bind mount，创建独立 namespace 保持自身可见，监听 `SIGTERM`/`SIGINT`/`SIGHUP` 信号，收到信号后自动 umount 并退出。

**清理模式** `logd --cleanup <config_path>`

进入 init mount namespace，umount 所有之前 bind mount 的路径。用于异常恢复（进程崩溃后残留的 bind mount）。

## 进程清理机制

每次执行隐藏操作前后，Java 层自动扫描 `/proc` 目录，通过变量间接引用技术匹配 cmdline 中包含本应用二进制路径的残留 `sh`/`su` 进程并 `kill -9`。使用 shell 变量构造路径模式，确保清理进程自身的 cmdline 不匹配目标路径，避免自杀。

## 构建与编译

### 环境要求

- Android SDK 35 (Android 15)
- Android NDK r26+ (用于编译原生守护进程)
- JDK 8+
- Gradle 8.9+

### 编译原生守护进程

```bash
cd native
make           # 产出 ./guard (ARM64 ELF)
```

将编译产物复制到 Android 项目的 assets 目录：

```bash
cp guard ../app/src/main/assets/logd_arm64-v8a
```

### 构建 APK

```bash
./gradlew assembleRelease
```

生成的 APK 位于 `app/build/outputs/apk/release/app-release.apk`。

## 项目结构

```
GuardProject/
├── app/
│   ├── build.gradle                    # Android 模块构建配置
│   ├── proguard-rules.pro
│   └── src/main/
│       ├── AndroidManifest.xml
│       ├── assets/
│       │   └── logd_arm64-v8a          # 编译后的原生守护进程
│       ├── java/com/example/guard/
│       │   └── MainActivity.java       # 应用主界面与逻辑
│       └── res/
│           ├── drawable/               # 矢量图标
│           ├── mipmap-anydpi-v26/      # 自适应图标
│           └── values/                 # 主题与字符串
├── native/
│   ├── guard_src.c                     # 守护进程主源码
│   ├── guard.h                         # 共享头文件
│   ├── guard_storage.c                 # 存储隔离变体
│   └── Makefile                         # NDK 交叉编译
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
| 验证设备 | Android 16, Linux 6.6, Toybox 0.8.12 |

## 安全说明

- 不修改任何用户数据，仅在 VFS 层创建临时 bind mount
- 重启后所有 bind mount 自动消失（mount 表不持久化）
- 守护进程崩溃时残留的 bind mount 可通过清理模式或重启恢复
- 不使用 overlayfs，不修改文件系统结构

## License

MIT
