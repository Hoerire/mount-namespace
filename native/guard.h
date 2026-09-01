/*
 * guard.h - 共享头文件
 *
 * 两种隔离逻辑严格分离:
 *   1. 普通目录: simple_bind_hide() / simple_bind_unhide()
 *   2. Android/data: android_data_namespace_mount() / android_data_namespace_unmount()
 *
 * 不要在普通目录逻辑中处理 Android/data
 * 不要在 Android/data 逻辑中使用普通 bind 方法
 */

#ifndef GUARD_H
#define GUARD_H

#ifndef _GNU_SOURCE
#define _GNU_SOURCE
#endif

#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <unistd.h>
#include <errno.h>
#include <fcntl.h>
#include <sys/stat.h>
#include <sys/mount.h>
#include <sched.h>
#include <limits.h>
#include <stdarg.h>
#include <time.h>
#include <dirent.h>
#include <sys/wait.h>

/* ========== 常量 ========== */

#define MAX_PATH_LEN  512
#define MAX_HIDES     64
#define EMPTY_DIR     "/data/local/tmp/.guard_empty"

/* Android/data 路径前缀 */
#define ANDROID_DATA_PREFIX "/storage/emulated/0/Android/data/"

/* ========== namespace.c ========== */

/* 日志输出 (stderr, 带时间戳) */
void log_print(const char *fmt, ...);

/* 创建独立 mount namespace (unshare CLONE_NEWNS)
 * 返回: 0=成功, -1=失败 */
int create_mount_namespace(void);

/* 将所有挂载设为 private (MS_REC|MS_PRIVATE)
 * 切断与宿主 namespace 的传播
 * 返回: 0=成功, -1=失败 */
int make_mounts_private(void);

/* 获取当前 mount namespace ID
 * 返回: 0=成功, -1=失败 */
int get_namespace_id(char *buf, size_t bufsize);

/* 验证 namespace 是否变化
 * 返回: 1=已变化, 0=未变化 */
int verify_namespace_changed(const char *before, const char *after);

/* 确保 empty_dir 存在 */
void ensure_empty_dir(void);

/* ========== mount_simple.c ========== */

/* 普通目录 bind mount 隐藏
 *
 * 适用: /data/local/tmp/xxx, /cache/xxx 等非 FUSE 路径
 * 不适用: /storage/emulated/0/Android/data/com.xxx (FUSE 路径)
 *
 * 参数:
 *   source - 空目录路径 (通常为 EMPTY_DIR)
 *   target - 要隐藏的目标目录
 *
 * 返回: 0=成功, -1=失败
 */
int simple_bind_hide(const char *source, const char *target);

/* 普通目录 bind mount 恢复
 *
 * 参数:
 *   target - 已隐藏的目标目录
 *
 * 返回: 0=成功, -1=失败
 */
int simple_bind_unhide(const char *target);

/* 判断路径是否为 Android/data 路径
 * 返回: 1=是, 0=否 */
int is_android_data_path(const char *path);

/* ========== mount_android_data.c ========== */

/* Android/data 专用 namespace 隔离
 *
 * 适用: /storage/emulated/0/Android/data/com.xxx
 * 不适用: 普通目录 (使用 simple_bind_hide)
 *
 * 流程:
 *   1. 在 init namespace 中: self-bind + make-private + bind-empty
 *      (所有 app 看到空目录, 不破坏 FUSE, 不影响 /mnt/user)
 *   2. unshare 创建新 namespace (继承 bind mounts)
 *   3. 在新 namespace 中: umount bind-empty (guard 看到真实内容)
 *   4. 保持 namespace 存活
 *
 * 效果:
 *   - 其他 namespace: 看到空目录
 *   - guard namespace: 看到真实内容
 *
 * 参数:
 *   package_path - /storage/emulated/0/Android/data/com.xxx
 *
 * 返回: 0=成功, -1=失败
 */
int android_data_namespace_mount(const char *package_path);

/* Android/data namespace 恢复
 *
 * 在 init namespace 中清理 bind mounts
 *
 * 参数:
 *   package_path - /storage/emulated/0/Android/data/com.xxx
 *
 * 返回: 0=成功, -1=失败
 */
int android_data_namespace_unmount(const char *package_path);

/* 在 guard namespace 中恢复真实内容
 * (umount bind-empty, 仅影响当前 namespace)
 *
 * 参数:
 *   package_path - /storage/emulated/0/Android/data/com.xxx
 *
 * 返回: 0=成功, -1=失败
 */
int android_data_reveal_in_namespace(const char *package_path);

#endif /* GUARD_H */
