/*
 * logd_storage.c - Android 16 FUSE Storage Mount Namespace 隔离方案
 *
 * 完整实现规范:
 *   Phase 1 预检查: FUSE 会话状态、vold PID、vold FD
 *   Phase 2 Namespace: unshare(CLONE_NEWNS) + MS_REC|MS_PRIVATE
 *   Phase 3 隔离: tmpfs 覆盖 (主) → bind mount (备) → umount2 (末)
 *   Phase 4 验证: mountinfo + 实际目录访问 + FUSE 会话
 *   Phase 5 恢复: 可逆向操作
 *
 * 层次关系:
 *   mount namespace → mount propagation → FUSE mount → vold userspace session
 *   → /storage/emulated → /storage/emulated/0
 *
 * 环境: Android 16 SDK 36 ARM64, Linux 6.6, Toybox 0.8.12
 * 依赖: root 权限, 无第三方库, 无 Magisk/KSU/APatch
 *
 * 安全保证:
 *   - /storage/emulated 是 FUSE, 不是真实磁盘
 *   - /storage/self/primary 只是符号链接
 *   - 不通过重新 bind 失效的 FUSE endpoint 来恢复存储
 *   - "Transport endpoint is not connected" 首先考虑 FUSE userspace session
 *   - 不随意 kill vold; 恢复 vold 优先使用 setprop ctl.restart vold
 *   - 不直接执行 /system/bin/vold
 *   - 不依赖 util-linux umount -R (Toybox 不支持)
 *   - 不修改 /data、真实 userdata 分区或 F2FS
 *   - 每一步只进行一个操作, 然后验证结果
 *   - mount namespace 隔离考虑 mount propagation 和子挂载继承
 *   - 区分: mount namespace 记录 / FUSE userspace session / vold 管理挂载 / /data 真实数据
 */

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

/* ========== 日志 ========== */

static void log_print(const char *fmt, ...)
{
    struct timespec ts;
    struct tm tm;
    char time_buf[32];

    if (clock_gettime(CLOCK_REALTIME, &ts) == 0) {
        localtime_r(&ts.tv_sec, &tm);
        strftime(time_buf, sizeof(time_buf), "%Y-%m-%d %H:%M:%S", &tm);
        fprintf(stderr, "[%s.%03ld] [logd] ", time_buf, ts.tv_nsec / 1000000);
    }

    va_list ap;
    va_start(ap, fmt);
    vfprintf(stderr, fmt, ap);
    va_end(ap);
    fflush(stderr);
}

/* ========== Mount Info 解析 ========== */

#define MAX_MOUNTS  256
#define MAX_MNT_LEN 512

struct mount_entry {
    int  mount_id;
    int  parent_id;
    char mount_point[MAX_MNT_LEN];
    char fs_type[64];       /* 文件系统类型 (fuse, tmpfs, ext4 等) */
    char source[MAX_MNT_LEN]; /* 挂载源 */
    int  depth;              /* 路径深度: '/' 的个数, 用于排序 */
};

/*
 * 解析 /proc/self/mountinfo
 * 只提取 mount_point 以 prefix 开头的条目
 * 返回: 匹配的条目数, -1 表示出错
 *
 * mountinfo 格式 (以空格分隔):
 *   mount_id parent_id major:minor root mount_point options ...
 *   ... - type source super_options
 * 示例:
 *   9508 218 0:234 / /storage/emulated rw,nosuid,... shared:87 - fuse /dev/fuse rw,...
 */
static int parse_mountinfo(struct mount_entry *entries, int max_entries,
                           const char *prefix)
{
    FILE *f = fopen("/proc/self/mountinfo", "r");
    if (!f) {
        log_print("ERROR: cannot open /proc/self/mountinfo: %s\n",
                  strerror(errno));
        return -1;
    }

    int count = 0;
    char line[4096];
    int prefix_len = strlen(prefix);

    while (fgets(line, sizeof(line), f) && count < max_entries) {
        int mnt_id, pnt_id;
        char major_minor[32];
        char root[MAX_MNT_LEN];
        char mount_point[MAX_MNT_LEN];

        if (sscanf(line, "%d %d %31s %511s %511s",
                   &mnt_id, &pnt_id, major_minor, root, mount_point) != 5) {
            continue;
        }

        /* 检查是否以 prefix 开头 */
        if (strncmp(mount_point, prefix, prefix_len) != 0)
            continue;

        /* 精确匹配: 要么完全相等, 要么 prefix 后跟 '/' */
        int mp_len = strlen(mount_point);
        if (mp_len == prefix_len || mount_point[prefix_len] == '/') {
            /* 计算路径深度 */
            int depth = 0;
            for (int i = 0; i < mp_len; i++) {
                if (mount_point[i] == '/') depth++;
            }

            entries[count].mount_id  = mnt_id;
            entries[count].parent_id = pnt_id;
            strncpy(entries[count].mount_point, mount_point, MAX_MNT_LEN - 1);
            entries[count].mount_point[MAX_MNT_LEN - 1] = '\0';
            entries[count].depth = depth;

            /* 解析 fs_type 和 source (在 '-' 之后) */
            entries[count].fs_type[0] = '\0';
            entries[count].source[0] = '\0';
            char *dash = strstr(line, " - ");
            if (dash) {
                char *after_dash = dash + 3;
                char fs_type[64] = {0};
                char source[MAX_MNT_LEN] = {0};
                /* 读取 type 和 source */
                if (sscanf(after_dash, "%63s %511s", fs_type, source) >= 1) {
                    strncpy(entries[count].fs_type, fs_type, 63);
                    strncpy(entries[count].source, source, MAX_MNT_LEN - 1);
                }
            }

            count++;
        }
    }

    fclose(f);
    return count;
}

/* qsort 比较函数: 深度降序 (子挂载在前) */
static int cmp_depth_desc(const void *a, const void *b)
{
    const struct mount_entry *ea = (const struct mount_entry *)a;
    const struct mount_entry *eb = (const struct mount_entry *)b;
    return eb->depth - ea->depth;
}

/* ========== Namespace 工具 ========== */

/* 读取 namespace 链接, 写入 buf, 返回字符串长度 */
static int read_ns(const char *path, char *buf, size_t bufsize)
{
    ssize_t len = readlink(path, buf, bufsize - 1);
    if (len < 0) return -1;
    buf[len] = '\0';
    return (int)len;
}

/* ========== FUSE / vold 诊断 ========== */

/*
 * 检查 FUSE 会话是否仍然活跃
 * 通过访问 /mnt/user/0/emulated 来判断
 *
 * 返回:
 *   1  = 活跃 (可访问)
 *   0  = 断开 (Transport endpoint is not connected, errno == ENOTCONN)
 *  -1  = 不存在或其它错误
 */
static int check_fuse_alive(void)
{
    struct stat st;
    if (stat("/mnt/user/0/emulated", &st) != 0) {
        if (errno == ENOTCONN)
            return 0;  /* Transport endpoint is not connected */
        return -1;     /* 不存在或其它错误 */
    }
    return 1;  /* 可访问, FUSE 会话活跃 */
}

/*
 * 检查 /storage/emulated 是否可正常访问
 *
 * 返回:
 *   1  = 可访问 (有内容)
 *   0  = 可访问但为空
 *  -1  = 不可访问 (Transport endpoint / 权限等)
 */
static int check_storage_accessible(void)
{
    DIR *d = opendir("/storage/emulated");
    if (!d) {
        return -1;  /* 不可访问 */
    }
    struct dirent *e;
    int has_content = 0;
    while ((e = readdir(d)) != NULL) {
        if (strcmp(e->d_name, ".") != 0 && strcmp(e->d_name, "..") != 0) {
            has_content = 1;
            break;
        }
    }
    closedir(d);
    return has_content;
}

/*
 * 获取 vold PID
 * 返回: vold 的 PID, -1 表示未找到
 */
static int get_vold_pid(void)
{
    FILE *f = popen("pidof vold 2>/dev/null", "r");
    if (!f) return -1;
    char buf[64];
    int pid = -1;
    if (fgets(buf, sizeof(buf), f)) {
        pid = atoi(buf);
    }
    pclose(f);
    return pid;
}

/*
 * 检查 vold 的 FD 中是否有 FUSE 相关的
 * 返回: 1=有, 0=无, -1=错误
 */
static int check_vold_fuse_fd(int vold_pid)
{
    if (vold_pid <= 0) return -1;

    char path[256];
    snprintf(path, sizeof(path), "/proc/%d/fd", vold_pid);

    DIR *d = opendir(path);
    if (!d) return -1;

    int found = 0;
    struct dirent *e;
    while ((e = readdir(d)) != NULL) {
        char link_path[512];
        snprintf(link_path, sizeof(link_path), "%s/%s", path, e->d_name);
        char target[512];
        ssize_t len = readlink(link_path, target, sizeof(target) - 1);
        if (len > 0) {
            target[len] = '\0';
            if (strstr(target, "fuse") || strstr(target, "/dev/fuse")) {
                found = 1;
                break;
            }
        }
    }
    closedir(d);
    return found;
}

/*
 * 执行 setprop ctl.restart vold (通过 init 的服务控制机制)
 * 注意: 这会影响所有 namespace 中的 FUSE 挂载
 * 仅在 FUSE 会话断开时使用
 */
static int restart_vold_via_init(void)
{
    log_print("[vold恢复] 通过 init 重启 vold: setprop ctl.restart vold\n");
    log_print("[vold恢复] 注意: 不要直接执行 /system/bin/vold\n");

    pid_t pid = fork();
    if (pid == 0) {
        /* 子进程: 执行 setprop */
        execlp("setprop", "setprop", "ctl.restart", "vold", NULL);
        _exit(127);
    }
    if (pid < 0) {
        log_print("[vold恢复] fork 失败: %s\n", strerror(errno));
        return -1;
    }

    int status;
    waitpid(pid, &status, 0);
    if (WIFEXITED(status) && WEXITSTATUS(status) == 0) {
        log_print("[vold恢复] setprop 已发送, 等待 vold 重启...\n");
        return 0;
    }
    log_print("[vold恢复] setprop 执行失败\n");
    return -1;
}

/*
 * Phase 1: 隔离前预检查
 *
 * 检查项:
 *   1. /storage/emulated 挂载是否存在
 *   2. /mnt/user/0/emulated FUSE 会话是否活跃
 *   3. vold PID 是否存在
 *   4. vold FD 中是否有 FUSE 相关
 *
 * 返回: 0=就绪, -1=需要先恢复 FUSE 会话
 */
static int pre_check_storage(void)
{
    log_print("=== Phase 1: 隔离前预检查 ===\n");

    /* 1. 检查 /storage/emulated 挂载 */
    struct mount_entry entries[MAX_MOUNTS];
    int count = parse_mountinfo(entries, MAX_MOUNTS, "/storage/emulated");
    if (count <= 0) {
        log_print("[预检查] /storage/emulated 无挂载, 可能已被卸载\n");
    } else {
        log_print("[预检查] /storage/emulated 有 %d 个挂载\n", count);
        for (int i = 0; i < count; i++) {
            log_print("[预检查]   [%d] %s type=%s source=%s\n",
                      i, entries[i].mount_point,
                      entries[i].fs_type[0] ? entries[i].fs_type : "?",
                      entries[i].source[0] ? entries[i].source : "?");
        }
    }

    /* 2. 检查 FUSE 会话 (/mnt/user/0/emulated) */
    int fuse_alive = check_fuse_alive();
    if (fuse_alive == 1) {
        log_print("[预检查] FUSE 会话活跃 (/mnt/user/0/emulated 可访问)\n");
    } else if (fuse_alive == 0) {
        log_print("[预检查] 警告: FUSE 会话已断开 (Transport endpoint is not connected)\n");
        log_print("[预检查] 原因不是路径不存在, 而是 FUSE userspace session 已断开\n");
    } else {
        log_print("[预检查] /mnt/user/0/emulated 不存在或不可访问 (errno=%d: %s)\n",
                  errno, strerror(errno));
    }

    /* 3. 检查 vold PID */
    int vold_pid = get_vold_pid();
    if (vold_pid > 0) {
        log_print("[预检查] vold PID=%d\n", vold_pid);

        /* 4. 检查 vold FD */
        int has_fuse_fd = check_vold_fuse_fd(vold_pid);
        if (has_fuse_fd == 1) {
            log_print("[预检查] vold FD 中有 FUSE 相关句柄\n");
        } else if (has_fuse_fd == 0) {
            log_print("[预检查] vold FD 中未发现 FUSE 相关句柄\n");
            log_print("[预检查] (注意: 无输出并不能单独证明所有 FUSE 都不存在)\n");
        }
    } else {
        log_print("[预检查] vold 未运行! \n");
        log_print("[预检查] 恢复方法: setprop ctl.restart vold\n");
    }

    /* 5. 检查 /storage/emulated 实际访问 */
    int accessible = check_storage_accessible();
    if (accessible == 1) {
        log_print("[预检查] /storage/emulated 可访问且有内容\n");
    } else if (accessible == 0) {
        log_print("[预检查] /storage/emulated 可访问但为空\n");
    } else {
        log_print("[预检查] /storage/emulated 不可访问 (可能 FUSE 断开)\n");
    }

    /* 6. 检查 /storage/self/primary 符号链接 */
    struct stat ps;
    if (lstat("/storage/self/primary", &ps) == 0) {
        if (S_ISLNK(ps.st_mode)) {
            char buf[PATH_MAX];
            ssize_t slen = readlink("/storage/self/primary", buf, sizeof(buf) - 1);
            if (slen > 0) {
                buf[slen] = '\0';
                log_print("[预检查] /storage/self/primary -> %s (符号链接正常)\n", buf);
            }
        } else {
            log_print("[预检查] /storage/self/primary 不是符号链接\n");
        }
    } else {
        log_print("[预检查] /storage/self/primary 不存在\n");
    }

    /* 综合判断 */
    if (fuse_alive == 0) {
        log_print("[预检查] 结论: FUSE 会话已断开, 建议先恢复再隔离\n");
        log_print("[预检查] 恢复步骤: setprop ctl.restart vold → 等待 → 重新预检查\n");
        return -1;
    }

    log_print("[预检查] 结论: 就绪, 可以进行隔离\n");
    return 0;
}

/* ========== 存储隔离核心 ========== */

/*
 * 存储隔离方法
 *   0 = 未隔离
 *   1 = tmpfs 覆盖 (保留 FUSE 会话, 最安全)
 *   2 = bind mount 空目录覆盖 (保留 FUSE 会话)
 *   3 = umount2 MNT_DETACH (可能影响 FUSE 会话, 末选)
 */
static int g_storage_method = 0;

/* 检查目录是否为空 (用于验证隔离效果) */
static int is_dir_empty(const char *path)
{
    DIR *d = opendir(path);
    if (!d) return -1;
    struct dirent *e;
    while ((e = readdir(d)) != NULL) {
        if (strcmp(e->d_name, ".") != 0 && strcmp(e->d_name, "..") != 0) {
            closedir(d);
            return 0;  /* 非空 */
        }
    }
    closedir(d);
    return 1;  /* 空 */
}

/*
 * Phase 3: 在已创建的独立 mount namespace 中隔离 /storage/emulated
 *
 * 前提: 调用者已执行 unshare(CLONE_NEWNS) 并设置 MS_PRIVATE
 *
 * 隔离策略 (优先级从高到低):
 *   1. tmpfs 覆盖 (主方式):
 *      - 在 VFS 层用 tmpfs 覆盖 /storage/emulated
 *      - FUSE 会话完全不受影响, vold 继续运行
 *      - 不修改 /data, 不创建临时文件
 *      - 子挂载自动被覆盖
 *      - 恢复简单 (umount tmpfs)
 *   2. bind mount 空目录 (备方式):
 *      - 用空目录 bind 覆盖 /storage/emulated
 *      - FUSE 会话不受影响
 *      - 需要在 /data/local/tmp 创建临时目录
 *   3. umount2 MNT_DETACH (末方式):
 *      - 仅在上述方式都失败时使用
 *      - 可能导致 FUSE 会话断开
 *      - 需要逐层处理子挂载
 *      - 需要检查 FUSE 会话是否受影响
 */
static int detach_storage_fuse(void)
{
    log_print("=== Phase 3: 存储隔离 ===\n");

    /* 1. 检测 /storage/emulated 挂载 */
    struct mount_entry entries[MAX_MOUNTS];
    int count = parse_mountinfo(entries, MAX_MOUNTS, "/storage/emulated");

    if (count <= 0) {
        log_print("[存储隔离] /storage/emulated 无挂载, 跳过隔离\n");
        goto ensure_dirs;
    }
    log_print("[存储隔离] 检测到 %d 个 /storage/emulated 挂载\n", count);

    qsort(entries, count, sizeof(struct mount_entry), cmp_depth_desc);
    for (int i = 0; i < count; i++) {
        log_print("[存储隔离]   [%d] %s (depth=%d, type=%s)\n",
                  i, entries[i].mount_point, entries[i].depth,
                  entries[i].fs_type[0] ? entries[i].fs_type : "?");
    }

    /* 2. 隔离前再次检查 FUSE 会话 */
    int fuse_before = check_fuse_alive();
    log_print("[存储隔离] 隔离前 FUSE 会话状态: %s\n",
              fuse_before == 1 ? "活跃" :
              fuse_before == 0 ? "断开(ENOTCONN)" : "不存在");

    /* === 方式 1: tmpfs 覆盖 (主方式, 不修改 /data) === */
    log_print("[存储隔离] 尝试方式 1: tmpfs 覆盖 (不修改 /data)\n");
    if (mount("tmpfs", "/storage/emulated", "tmpfs",
              MS_NOSUID, "size=1k,mode=755") == 0) {
        /* tmpfs 挂载成功, 创建空 /0 子目录保持结构兼容 */
        mkdir("/storage/emulated/0", 0771);
        /* 设为只读, 防止写入 */
        mount(NULL, "/storage/emulated", NULL,
              MS_REMOUNT | MS_RDONLY | MS_NOSUID, NULL);
        g_storage_method = 1;
        log_print("[存储隔离] tmpfs 覆盖成功 (FUSE 会话保留, /data 未修改)\n");
        goto verify;
    }
    log_print("[存储隔离] tmpfs 覆盖失败: errno=%d (%s)\n",
              errno, strerror(errno));

    /* === 方式 2: bind mount 空目录 (备方式) === */
    log_print("[存储隔离] 尝试方式 2: bind mount 空目录覆盖\n");
    {
        const char *temp_base = "/data/local/tmp/.logd_storage";
        mkdir("/data/local/tmp", 0755);
        mkdir(temp_base, 0755);
        char temp_0[MAX_MNT_LEN];
        snprintf(temp_0, sizeof(temp_0), "%s/0", temp_base);
        mkdir(temp_0, 0771);

        if (mount(temp_base, "/storage/emulated", NULL, MS_BIND, NULL) == 0) {
            mount(NULL, "/storage/emulated", NULL,
                  MS_BIND | MS_REMOUNT | MS_RDONLY, NULL);
            g_storage_method = 2;
            log_print("[存储隔离] bind mount 成功 (FUSE 会话保留)\n");
            goto verify;
        }
        log_print("[存储隔离] bind mount 失败: errno=%d (%s)\n",
                  errno, strerror(errno));
    }

    /* === 方式 3: umount2 MNT_DETACH (末方式, 可能影响 FUSE 会话) === */
    log_print("[存储隔离] 尝试方式 3: umount2 MNT_DETACH (可能影响 FUSE 会话)\n");

    /* 3a. 从最深层子挂载开始逐个 detach */
    for (int i = 0; i < count; i++) {
        if (strcmp(entries[i].mount_point, "/storage/emulated") == 0)
            continue;
        log_print("[存储隔离] 卸载子挂载: %s\n", entries[i].mount_point);
        umount2(entries[i].mount_point, MNT_DETACH);
    }

    /* 3b. 卸载 FUSE 主挂载 */
    log_print("[存储隔离] 卸载 FUSE: /storage/emulated (umount2 MNT_DETACH)\n");
    if (umount2("/storage/emulated", MNT_DETACH) != 0) {
        log_print("[存储隔离] /storage/emulated 卸载失败: errno=%d (%s), 重试…\n",
                  errno, strerror(errno));
        /* 回退: 再次清理可能残留的子挂载 */
        struct mount_entry retry[MAX_MOUNTS];
        int rc = parse_mountinfo(retry, MAX_MOUNTS, "/storage/emulated");
        if (rc > 0) {
            qsort(retry, rc, sizeof(struct mount_entry), cmp_depth_desc);
            for (int i = 0; i < rc; i++)
                umount2(retry[i].mount_point, MNT_DETACH);
            if (umount2("/storage/emulated", MNT_DETACH) != 0) {
                log_print("[存储隔离] FATAL: /storage/emulated 仍然 busy\n");
                log_print("[存储隔离] 恢复方法: setprop ctl.restart vold\n");
                return -1;
            }
        } else {
            return -1;
        }
    }
    log_print("[存储隔离] /storage/emulated 已卸载 (umount2)\n");
    g_storage_method = 3;

    /* 3c. 检查 FUSE 会话是否受影响 */
    {
        int fuse_after = check_fuse_alive();
        log_print("[存储隔离] 隔离后 FUSE 会话状态: %s\n",
                  fuse_after == 1 ? "活跃" :
                  fuse_after == 0 ? "断开(ENOTCONN)" : "不存在");

        if (fuse_after == 0 && fuse_before == 1) {
            /* FUSE 会话在 umount 过程中断开 */
            log_print("[存储隔离] 警告: FUSE 会话在 umount 过程中断开\n");
            log_print("[存储隔离] 原因: /storage/emulated 与 /mnt/user/0/emulated\n");
            log_print("[存储隔离]   可能共享 /dev/fuse 设备, 卸载一侧影响另一侧\n");
            log_print("[存储隔离] 恢复方法: setprop ctl.restart vold\n");
            log_print("[存储隔离] (不要直接执行 /system/bin/vold)\n");
            log_print("[存储隔离] 注意: 此恢复会影响所有 namespace\n");
        } else if (fuse_after == 1) {
            log_print("[存储隔离] FUSE 会话仍活跃 (/mnt/user/0/emulated 可访问)\n");
        }
    }

verify:
    /* === Phase 4: 验证隔离结果 === */
    log_print("=== Phase 4: 验证隔离结果 ===\n");

    /* 4a. 检查实际目录访问 (不只看 mount 表) */
    int accessible = check_storage_accessible();
    if (g_storage_method == 1 || g_storage_method == 2) {
        /* tmpfs/bind 方式: 目录应存在且为空 */
        if (accessible == 0) {
            log_print("[验证] /storage/emulated 可访问且为空 → 隔离成功\n");
        } else if (accessible == 1) {
            log_print("[验证] 警告: /storage/emulated 非空, 隔离可能未生效\n");
        } else {
            log_print("[验证] 警告: /storage/emulated 不可访问\n");
        }

        /* 检查 /storage/emulated/0 是否为空 */
        int empty = is_dir_empty("/storage/emulated/0");
        if (empty == 1) {
            log_print("[验证] /storage/emulated/0 为空目录 → 隔离成功\n");
        } else if (empty == 0) {
            log_print("[验证] 警告: /storage/emulated/0 非空, 隔离可能未生效\n");
        } else {
            log_print("[验证] 警告: 无法访问 /storage/emulated/0\n");
        }
    } else if (g_storage_method == 3) {
        /* umount 方式: 目录可能不存在或为空 */
        if (accessible == -1) {
            log_print("[验证] /storage/emulated 不可访问 → umount 隔离生效\n");
        } else if (accessible == 0) {
            log_print("[验证] /storage/emulated 为空 → umount 隔离生效\n");
        }
    }

    /* 4b. 检查 mountinfo */
    struct mount_entry verify_entries[MAX_MOUNTS];
    int verify_count = parse_mountinfo(verify_entries, MAX_MOUNTS,
                                       "/storage/emulated");
    if (g_storage_method == 3 && verify_count > 0) {
        /* umount 方式: 不应残留任何挂载 */
        log_print("[验证] 警告: 仍有 %d 个挂载残留\n", verify_count);
        for (int i = 0; i < verify_count; i++) {
            log_print("[验证]   残留: %s (type=%s)\n",
                      verify_entries[i].mount_point,
                      verify_entries[i].fs_type[0] ? verify_entries[i].fs_type : "?");
        }
        return -1;
    }
    if ((g_storage_method == 1 || g_storage_method == 2) && verify_count > 0) {
        /* tmpfs/bind 方式: 应有覆盖挂载 */
        log_print("[验证] mountinfo 中有 %d 个条目 (覆盖挂载):\n", verify_count);
        for (int i = 0; i < verify_count; i++) {
            log_print("[验证]   %s type=%s source=%s\n",
                      verify_entries[i].mount_point,
                      verify_entries[i].fs_type[0] ? verify_entries[i].fs_type : "?",
                      verify_entries[i].source[0] ? verify_entries[i].source : "?");
        }
    }
    if (verify_count == 0 && g_storage_method == 0) {
        log_print("[验证] /storage/emulated 无挂载 (未隔离)\n");
    }

    /* 4c. 检查 FUSE 会话最终状态 */
    int fuse_final = check_fuse_alive();
    log_print("[验证] 最终 FUSE 会话状态: %s\n",
              fuse_final == 1 ? "活跃" :
              fuse_final == 0 ? "断开(ENOTCONN)" : "不存在");

ensure_dirs: ;
    /* 5. 确保目录结构存在 */
    struct stat st;
    if (stat("/storage/emulated", &st) != 0) {
        log_print("[存储隔离] 创建 /storage/emulated 目录\n");
        mkdir("/storage", 0755);
        mkdir("/storage/emulated", 0755);
    }
    if (stat("/storage/emulated/0", &st) != 0) {
        log_print("[存储隔离] 创建 /storage/emulated/0 目录\n");
        mkdir("/storage/emulated/0", 0755);
    }

    /* 6. 确保 /storage/self/primary symlink 正确解析 */
    struct stat ps;
    if (lstat("/storage/self/primary", &ps) == 0) {
        if (S_ISLNK(ps.st_mode)) {
            char buf[PATH_MAX];
            ssize_t slen = readlink("/storage/self/primary", buf, sizeof(buf) - 1);
            if (slen > 0) {
                buf[slen] = '\0';
                log_print("[存储隔离] /storage/self/primary -> %s (符号链接正常)\n", buf);
            }
        }
    } else {
        log_print("[存储隔离] 创建 /storage/self/primary -> /storage/emulated/0\n");
        mkdir("/storage/self", 0755);
        symlink("/storage/emulated/0", "/storage/self/primary");
    }

    /* 7. 最终状态报告 */
    if (g_storage_method > 0) {
        const char *method_name =
            g_storage_method == 1 ? "tmpfs 覆盖" :
            g_storage_method == 2 ? "bind mount" :
            "umount2 MNT_DETACH";
        log_print("[存储隔离] ACTIVE (方式=%s)\n", method_name);
    }
    return 0;
}

/*
 * Phase 5: 恢复 (逆向操作, 用于异常恢复)
 *
 * 在独立 namespace 中撤销隔离, 恢复 /storage/emulated 访问
 *
 * 注意: 此函数仅在独立 namespace 中有效
 *       如果 namespace 已被销毁, 隔离自动解除
 */
static int restore_storage_fuse(void)
{
    log_print("=== Phase 5: 恢复存储访问 ===\n");

    if (g_storage_method == 0) {
        log_print("[恢复] 未隔离, 无需恢复\n");
        return 0;
    }

    if (g_storage_method == 1 || g_storage_method == 2) {
        /* tmpfs / bind mount 方式: umount 覆盖挂载即可恢复 */
        log_print("[恢复] 卸载覆盖挂载 (方式=%d)\n", g_storage_method);

        /* 先卸载子挂载 (如果有) */
        struct mount_entry sub[MAX_MOUNTS];
        int sub_count = parse_mountinfo(sub, MAX_MOUNTS, "/storage/emulated");
        if (sub_count > 0) {
            qsort(sub, sub_count, sizeof(struct mount_entry), cmp_depth_desc);
            for (int i = 0; i < sub_count; i++) {
                if (strcmp(sub[i].mount_point, "/storage/emulated") == 0)
                    continue;
                log_print("[恢复] 卸载子挂载: %s\n", sub[i].mount_point);
                umount2(sub[i].mount_point, MNT_DETACH);
            }
        }

        /* 卸载主覆盖挂载 */
        if (umount2("/storage/emulated", MNT_DETACH) == 0) {
            log_print("[恢复] 覆盖挂载已卸载, /storage/emulated 已恢复\n");
            g_storage_method = 0;

            /* 验证恢复 */
            int accessible = check_storage_accessible();
            if (accessible == 1) {
                log_print("[恢复] 验证: /storage/emulated 可访问且有内容 → 恢复成功\n");
            } else if (accessible == 0) {
                log_print("[恢复] 验证: /storage/emulated 可访问但为空\n");
            } else {
                log_print("[恢复] 验证: /storage/emulated 不可访问\n");
                int fuse = check_fuse_alive();
                if (fuse == 0) {
                    log_print("[恢复] FUSE 会话已断开, 需要: setprop ctl.restart vold\n");
                }
            }
            return 0;
        } else {
            log_print("[恢复] 卸载失败: errno=%d (%s)\n", errno, strerror(errno));
            return -1;
        }
    }

    if (g_storage_method == 3) {
        /* umount 方式: 尝试从 /mnt/user/0/emulated 重新 bind */
        log_print("[恢复] 尝试从 /mnt/user/0/emulated 重新 bind\n");

        int fuse = check_fuse_alive();
        if (fuse == 1) {
            /* FUSE 会话仍活跃, 可以 bind */
            if (mount("/mnt/user/0/emulated", "/storage/emulated",
                      NULL, MS_BIND, NULL) == 0) {
                log_print("[恢复] bind 成功: /mnt/user/0/emulated → /storage/emulated\n");
                g_storage_method = 0;

                /* 验证 */
                int accessible = check_storage_accessible();
                if (accessible == 1) {
                    log_print("[恢复] 验证: /storage/emulated 可访问且有内容 → 恢复成功\n");
                } else {
                    log_print("[恢复] 验证: /storage/emulated 可访问但为空\n");
                }
                return 0;
            } else {
                log_print("[恢复] bind 失败: errno=%d (%s)\n", errno, strerror(errno));
            }
        } else if (fuse == 0) {
            /* FUSE 会话断开, 不能简单 bind */
            log_print("[恢复] FUSE 会话已断开 (Transport endpoint is not connected)\n");
            log_print("[恢复] 不能简单 bind, 需要让 vold 重新建立 FUSE session\n");
            log_print("[恢复] 执行: setprop ctl.restart vold\n");
            log_print("[恢复] (不要直接执行 /system/bin/vold)\n");

            /* 尝试通过 init 重启 vold */
            if (restart_vold_via_init() == 0) {
                /* 等待 vold 重启 */
                log_print("[恢复] 等待 vold 重启 (5秒)…\n");
                sleep(5);

                /* 检查 vold 是否已重启 */
                int new_pid = get_vold_pid();
                if (new_pid > 0) {
                    log_print("[恢复] vold 已重启, PID=%d\n", new_pid);
                } else {
                    log_print("[恢复] vold 仍未运行, 等待额外 5秒…\n");
                    sleep(5);
                    new_pid = get_vold_pid();
                    if (new_pid > 0) {
                        log_print("[恢复] vold 已重启, PID=%d\n", new_pid);
                    } else {
                        log_print("[恢复] vold 仍未运行, 恢复失败\n");
                        return -1;
                    }
                }

                /* 再次检查 FUSE 会话 */
                fuse = check_fuse_alive();
                if (fuse == 1) {
                    log_print("[恢复] FUSE 会话已重新建立\n");
                    /* 尝试 bind */
                    if (mount("/mnt/user/0/emulated", "/storage/emulated",
                              NULL, MS_BIND, NULL) == 0) {
                        log_print("[恢复] bind 成功\n");
                        g_storage_method = 0;
                        return 0;
                    }
                } else {
                    log_print("[恢复] FUSE 会话仍未恢复\n");
                    return -1;
                }
            }
        } else {
            log_print("[恢复] /mnt/user/0/emulated 不存在\n");
        }
        return -1;
    }

    return 0;
}

/* ========== 主函数 ========== */

int main(int argc, char *argv[])
{
    log_print("=== Android 16 FUSE Storage Mount Namespace 隔离方案 ===\n");
    log_print("环境: Android 16 SDK 36 ARM64, Linux 6.6, Toybox 0.8.12\n");

    /* 检查 root 权限 */
    if (getuid() != 0) {
        log_print("ERROR: 必须以 root 权限运行\n");
        return 1;
    }

    /* --- 记录宿主 namespace --- */
    char host_ns[256];
    if (read_ns("/proc/self/ns/mnt", host_ns, sizeof(host_ns)) < 0) {
        log_print("ERROR: 无法读取宿主 namespace\n");
        return 1;
    }
    log_print("宿主 mount namespace: %s\n", host_ns);

    /* --- Phase 1: 隔离前预检查 --- */
    int pre_check = pre_check_storage();
    if (pre_check != 0) {
        log_print("预检查未通过, FUSE 会话需要先恢复\n");
        log_print("建议: setprop ctl.restart vold → 等待 → 重新运行\n");
        /* 继续执行, 隔离可能仍可工作 (bind/tmpfs 不依赖 FUSE 会话) */
        log_print("继续尝试隔离 (tmpfs/bind 不依赖 FUSE 会话)…\n");
    }

    /* --- Phase 2: 创建新的 Mount Namespace --- */
    log_print("=== Phase 2: 创建 Mount Namespace ===\n");
    log_print("执行 unshare(CLONE_NEWNS)…\n");
    if (unshare(CLONE_NEWNS) != 0) {
        log_print("ERROR: namespace 创建失败: %s (errno=%d)\n",
                  strerror(errno), errno);
        /*
         * 关键安全保证: unshare 失败时立即退出
         * 绝对不能在宿主 namespace 中执行 umount
         */
        log_print("安全保证: unshare 失败, 绝不触碰宿主 namespace, 退出\n");
        return 1;
    }

    /* --- 记录新 namespace --- */
    char logd_ns[256];
    if (read_ns("/proc/self/ns/mnt", logd_ns, sizeof(logd_ns)) < 0) {
        log_print("ERROR: 无法读取 logd namespace\n");
        return 1;
    }
    log_print("logd mount namespace: %s\n", logd_ns);

    /* --- 验证 namespace 确实不同 --- */
    if (strcmp(host_ns, logd_ns) == 0) {
        log_print("ERROR: namespace 未改变!\n");
        return 1;
    }
    log_print("Namespace 隔离: YES (宿主=%s, logd=%s)\n", host_ns, logd_ns);

    /* --- 设置 mount propagation 为 MS_PRIVATE --- */
    /*
     * 等同于 mount --make-rprivate /
     * 必须在 unshare 后立即执行, 切断与宿主 namespace 的挂载传播
     * 即使失败也不退出 (tmpfs/bind/umount2 仍可工作)
     */
    log_print("设置 mount propagation: MS_REC|MS_PRIVATE\n");
    if (mount(NULL, "/", NULL, MS_REC | MS_PRIVATE, NULL) != 0) {
        log_print("警告: MS_REC|MS_PRIVATE 失败: %s (errno=%d), 继续\n",
                  strerror(errno), errno);
        log_print("(注意: 传播未切断, 宿主 namespace 可能受影响)\n");
    } else {
        log_print("Mount propagation 已切断 (MS_REC|MS_PRIVATE)\n");
    }

    /* --- Phase 3 + 4: 隔离 + 验证 --- */
    if (detach_storage_fuse() != 0) {
        log_print("ERROR: 存储隔离失败\n");
        /* 尝试恢复 */
        log_print("尝试恢复…\n");
        restore_storage_fuse();
        return 1;
    }

    /* --- 最终 namespace 确认 (防止意外回退到宿主) --- */
    char final_ns[256];
    if (read_ns("/proc/self/ns/mnt", final_ns, sizeof(final_ns)) > 0) {
        log_print("最终 namespace 检查: %s\n", final_ns);
        if (strcmp(final_ns, host_ns) == 0) {
            log_print("ERROR: namespace 已回退到宿主!\n");
            return 1;
        }
        log_print("Namespace 隔离确认: YES\n");
    }

    log_print("=== 隔离完成 ===\n");

    /* --- 恢复模式 (测试用) --- */
    if (argc > 1 && strcmp(argv[1], "--restore") == 0) {
        log_print("恢复模式: 撤销隔离\n");
        if (restore_storage_fuse() != 0) {
            log_print("恢复失败\n");
            return 1;
        }
        log_print("恢复完成\n");
        return 0;
    }

    /* --- idle 模式 (保持 namespace 存活, 用于测试) --- */
    if (argc > 1 && strcmp(argv[1], "--idle") == 0) {
        log_print("Idle 模式: 保持 namespace 存活 (Ctrl-C 退出)\n");
        log_print("(namespace 销毁后隔离自动解除, 宿主不受影响)\n");
        pause();
    }

    return 0;
}
