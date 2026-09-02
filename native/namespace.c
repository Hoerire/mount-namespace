/*
 * namespace.c - Mount Namespace 创建与管理
 *
 * 职责:
 *   - 创建独立 mount namespace (unshare CLONE_NEWNS)
 *   - 设置 mount propagation 为 private (切断传播)
 *   - 获取/验证 namespace ID
 *   - 确保空目录存在
 *
 * 不涉及:
 *   - 具体的 bind mount 操作 (在 mount_simple.c / mount_android_data.c 中)
 *   - FUSE mount 处理
 *   - vold 管理
 */

#include "logd.h"

/* ========== 日志 ========== */

void log_print(const char *fmt, ...)
{
    struct timespec ts;
    struct tm tm;
    char time_buf[32];

    if (clock_gettime(CLOCK_REALTIME, &ts) == 0) {
        localtime_r(&ts.tv_sec, &tm);
        strftime(time_buf, sizeof(time_buf), "%H:%M:%S", &tm);
        fprintf(stderr, "[%s.%03ld] ", time_buf, ts.tv_nsec / 1000000);
    }

    va_list ap;
    va_start(ap, fmt);
    vfprintf(stderr, fmt, ap);
    va_end(ap);
    fflush(stderr);
}

/* ========== Namespace 管理 ========== */

/*
 * 创建独立 mount namespace
 *
 * 调用 unshare(CLONE_NEWNS), 内核创建新的 mount namespace
 * 新 namespace 是当前 namespace 的副本
 * 之后对 mount 的修改只影响新 namespace
 *
 * 返回: 0=成功, -1=失败
 */
int create_mount_namespace(void)
{
    log_print("[namespace] 正在创建 mount namespace...\n");

    if (unshare(CLONE_NEWNS) != 0) {
        log_print("[namespace] 错误: unshare(CLONE_NEWNS) 失败: errno=%d (%s)\n",
                  errno, strerror(errno));
        return -1;
    }

    log_print("[namespace] mount namespace 创建成功\n");
    return 0;
}

/*
 * 将所有挂载设为 private
 *
 * 等同于 mount --make-rprivate /
 * 切断当前 namespace 与宿主 namespace 之间的 mount propagation
 * 之后在当前 namespace 中的 mount/umount 操作不会传播到其他 namespace
 *
 * 返回: 0=成功, -1=失败 (但即使失败也继续, umount2 MNT_DETACH 仍可工作)
 */
int make_mounts_private(void)
{
    log_print("[namespace] 设置 MS_REC|MS_PRIVATE (切断传播)...\n");

    if (mount(NULL, "/", NULL, MS_REC | MS_PRIVATE, NULL) != 0) {
        log_print("[namespace] 警告: MS_PRIVATE 失败: errno=%d (%s), 继续\n",
                  errno, strerror(errno));
        return -1;
    }

    log_print("[namespace] MS_PRIVATE 设置成功\n");
    return 0;
}

/*
 * 获取当前 mount namespace ID
 *
 * 读取 /proc/self/ns/mnt 的符号链接目标
 * 例如: mnt:[4026536022]
 *
 * 返回: 0=成功, -1=失败
 */
int get_namespace_id(char *buf, size_t bufsize)
{
    ssize_t len = readlink("/proc/self/ns/mnt", buf, bufsize - 1);
    if (len < 0) {
        log_print("[namespace] 错误: 无法读取 namespace ID: %s\n",
                  strerror(errno));
        return -1;
    }
    buf[len] = '\0';
    return 0;
}

/*
 * 验证 namespace 是否已变化
 *
 * 比较两个 namespace ID 字符串
 *
 * 返回: 1=已变化, 0=未变化
 */
int verify_namespace_changed(const char *before, const char *after)
{
    if (strcmp(before, after) == 0) {
        log_print("[namespace] 错误: namespace 未变化!\n");
        return 0;
    }
    log_print("[namespace] 验证: namespace 已变化\n");
    return 1;
}

/*
 * 确保空目录存在
 *
 * 创建 /data/local/tmp/.logd_empty 用于 bind mount
 */
void ensure_empty_dir(void)
{
    struct stat st;

    /* 确保 /data/local/tmp 存在 */
    if (stat("/data/local/tmp", &st) != 0) {
        mkdir("/data/local/tmp", 0755);
    }

    /* 创建空目录 */
    if (stat(EMPTY_DIR, &st) != 0) {
        mkdir(EMPTY_DIR, 0755);
        log_print("[namespace] 已创建空目录: %s\n", EMPTY_DIR);
    }
}
