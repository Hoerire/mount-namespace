/*
 * guard.c - 存储隔离守护进程
 *
 * 功能:
 *   1. 读取配置文件 (hide: 路径列表)
 *   2. 进入 init mount namespace
 *   3. 在 init namespace 中 bind mount 空目录隐藏目标路径
 *   4. 创建独立 mount namespace, 在其中恢复可见 (guard 自身可见)
 *   5. 保持进程存活, 收到信号时退出并清理
 *   6. --cleanup 模式: 进入 init namespace, umount 所有 bind mounts
 *
 * 目录类型:
 *   /storage/emulated/N/Android/data/pkg → 特殊处理 (媒体路径 + FUSE 路径双保险)
 *   /storage/emulated/N/ (非 Android/data) → 直接 bind mount
 *   其他路径 → 直接 bind mount (非 FUSE, 无传播问题)
 *
 * 环境: Android 16 SDK 36 ARM64, Linux 6.6, Toybox 0.8.12
 */

#ifndef _GNU_SOURCE
#define _GNU_SOURCE
#endif
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <unistd.h>
#include <signal.h>
#include <errno.h>
#include <fcntl.h>
#include <sys/stat.h>
#include <sys/mount.h>
#include <sys/wait.h>
#include <sys/prctl.h>
#include <time.h>
#include <ctype.h>
#include <stdarg.h>
#include <dirent.h>
#include <sched.h>
#include <linux/sched.h>

/* ========== 全局配置 ========== */

static char g_config_path[512] = "/data/local/tmp/Guard/config.txt";

// 空目录路径 (放在 app 私有目录下)
static char g_empty_dir[512];

// 根据配置文件路径推导空目录路径
static void init_empty_dir(void) {
    strncpy(g_empty_dir, g_config_path, sizeof(g_empty_dir) - 1);
    g_empty_dir[sizeof(g_empty_dir) - 1] = '\0';
    char *slash = strrchr(g_empty_dir, '/');
    if (slash) {
        strcpy(slash + 1, ".guard_empty");
    } else {
        snprintf(g_empty_dir, sizeof(g_empty_dir), "/data/user/0/.guard_empty");
    }
}

#define MAX_TARGETS   64
#define MAX_PKG_LEN   256
#define MAX_HIDE_DIRS 32

static char g_hide_dirs[MAX_HIDE_DIRS][MAX_PKG_LEN];
static int  g_hide_dir_count = 0;

static char g_mounted_dirs[MAX_HIDE_DIRS * 2][MAX_PKG_LEN];
static int  g_mounted_count = 0;

static volatile int g_running = 1;
static int g_is_root = 0;

/* ========== 日志 ========== */

static void log_print(const char *fmt, ...)
{
    struct timespec ts;
    struct tm tm;
    char time_buf[32];

    if (clock_gettime(CLOCK_REALTIME, &ts) == 0) {
        localtime_r(&ts.tv_sec, &tm);
        strftime(time_buf, sizeof(time_buf), "%Y-%m-%d %H:%M:%S", &tm);
        fprintf(stderr, "[%s.%03ld] ", time_buf, ts.tv_nsec / 1000000);
    }

    va_list ap;
    va_start(ap, fmt);
    vfprintf(stderr, fmt, ap);
    va_end(ap);
    fflush(stderr);
}

/* ========== 进程名固定 ========== */

static void set_process_name(int argc, char **argv, int write_pid)
{
    const char *name = "logd";

    /* 覆写 argv[0], 清空其余 argv */
    if (argv[0]) {
        size_t total = 0;
        for (int i = 0; i < argc && argv[i]; i++) {
            total += strlen(argv[i]) + 1;
        }
        if (total > 0) {
            memset(argv[0], 0, total);
            strncpy(argv[0], name, total - 1);
            argv[0][total - 1] = '\0';
        }
    }

    /* 设置 /proc/self/comm */
    prctl(PR_SET_NAME, name, 0, 0, 0);

    if (write_pid) {
        char pid_file[512];
        strncpy(pid_file, g_config_path, sizeof(pid_file) - 1);
        pid_file[sizeof(pid_file) - 1] = '\0';
        char *slash = strrchr(pid_file, '/');
        if (slash) {
            strcpy(slash + 1, "logd.pid");
        } else {
            snprintf(pid_file, sizeof(pid_file), "/data/local/tmp/Guard/logd.pid");
        }
        FILE *f = fopen(pid_file, "w");
        if (f) {
            fprintf(f, "%d\n", (int)getpid());
            fclose(f);
            log_print("[进程] 名称: %s (pid=%d) → %s\n", name, (int)getpid(), pid_file);
        } else {
            log_print("[进程] 名称: %s (pid=%d)\n", name, (int)getpid());
        }
    } else {
        log_print("[进程] 名称: %s (pid=%d)\n", name, (int)getpid());
    }
}

/* ========== 命令执行 ========== */

static int exec_cmd(char *buf, size_t bufsize, char *const argv[])
{
    int pipefd[2];
    if (pipe(pipefd) < 0) return -1;

    pid_t pid = fork();
    if (pid < 0) {
        close(pipefd[0]);
        close(pipefd[1]);
        return -1;
    }

    if (pid != 0) {
        close(pipefd[1]);
        size_t total = 0;
        if (buf) buf[0] = '\0';
        for (;;) {
            char tmp[4096];
            ssize_t n = read(pipefd[0], tmp, sizeof(tmp));
            if (n <= 0) {
                if (n < 0 && errno == EINTR) continue;
                break;
            }
            if (buf && total < bufsize - 1) {
                size_t copy = (bufsize - 1 - total < (size_t)n) ? (bufsize - 1 - total) : (size_t)n;
                memcpy(buf + total, tmp, copy);
                total += copy;
            }
        }
        if (buf) buf[total < bufsize ? total : bufsize - 1] = '\0';
        close(pipefd[0]);

        int status;
        waitpid(pid, &status, 0);
        if (WIFEXITED(status)) return WEXITSTATUS(status);
        return -1;
    }

    close(pipefd[0]);
    dup2(pipefd[1], STDOUT_FILENO);
    dup2(pipefd[1], STDERR_FILENO);
    close(pipefd[1]);
    execvp(argv[0], argv);
    _exit(127);
}

/* ========== 字符串工具 ========== */

static char *trim(char *s)
{
    while (*s && isspace((unsigned char)*s)) s++;
    size_t len = strlen(s);
    while (len > 0 && isspace((unsigned char)s[len - 1])) {
        s[--len] = '\0';
    }
    return s;
}

/* ========== 配置文件 ========== */

static int load_config(void)
{
    FILE *f = fopen(g_config_path, "r");
    if (!f) {
        log_print("[错误] 无法打开配置文件 %s errno=%d\n", g_config_path, errno);
        return -1;
    }

    memset(g_hide_dirs, 0, sizeof(g_hide_dirs));
    g_hide_dir_count = 0;

    char line[4096];
    while (fgets(line, sizeof(line), f)) {
        char *s = trim(line);
        if (!*s || *s == '#') continue;

        /* 支持 key:value 和 key=value 两种格式 */
        char *sep = strchr(s, ':');
        if (!sep) sep = strchr(s, '=');
        if (!sep) continue;
        *sep = '\0';
        char *key = trim(s);
        char *val = trim(sep + 1);
        if (!*key || !*val) continue;

        if (strcmp(key, "hide") == 0 || strcmp(key, "hide_dir") == 0 ||
            strcmp(key, "custom") == 0) {
            if (g_hide_dir_count < MAX_HIDE_DIRS) {
                snprintf(g_hide_dirs[g_hide_dir_count++], MAX_PKG_LEN, "%s", val);
            }
        }
    }

    fclose(f);
    log_print("[配置] 已加载 %d 个隐藏路径\n", g_hide_dir_count);
    for (int i = 0; i < g_hide_dir_count; i++) {
        log_print("[配置]   [%d] %s\n", i, g_hide_dirs[i]);
    }
    return 0;
}

/* ========== 路径分类 ========== */

static int is_storage_path(const char *path)
{
    return strncmp(path, "/storage/", 9) == 0;
}

static int is_android_data_path(const char *path)
{
    const char *p = path;
    if (strncmp(p, "/storage/emulated/", 18) != 0)
        return 0;
    p += 18;
    while (*p >= '0' && *p <= '9')
        p++;
    if (strncmp(p, "/Android/data/", 14) == 0)
        return 1;
    return 0;
}

static int is_android_obb_path(const char *path)
{
    const char *p = path;
    if (strncmp(p, "/storage/emulated/", 18) != 0)
        return 0;
    p += 18;
    while (*p >= '0' && *p <= '9')
        p++;
    if (strncmp(p, "/Android/obb/", 13) == 0)
        return 1;
    return 0;
}

static int hide_list_has_storage_paths(void)
{
    for (int i = 0; i < g_hide_dir_count; i++) {
        if (is_storage_path(g_hide_dirs[i]))
            return 1;
    }
    return 0;
}

/* ========== 目录隐藏 ========== */

/* 挂载前: 将空目录属性设为与目标一致, 挂载后不再修改目标 */
static void sync_empty_attrs(struct stat *st)
{
    chown(g_empty_dir, st->st_uid, st->st_gid);
    chmod(g_empty_dir, st->st_mode & 07777);
    struct timespec times[2];
    times[0] = st->st_atim;
    times[1] = st->st_mtim;
    utimensat(AT_FDCWD, g_empty_dir, times, 0);
}

static void hide_directories(void)
{
    if (!g_is_root) {
        log_print("[隐藏] 跳过: 非 root\n");
        return;
    }

    struct stat empty_st;
    if (stat(g_empty_dir, &empty_st) != 0) {
        mkdir(g_empty_dir, 0755);
    }
    if (stat(g_empty_dir, &empty_st) == 0 && !S_ISDIR(empty_st.st_mode)) {
        unlink(g_empty_dir);
        mkdir(g_empty_dir, 0755);
    }

    g_mounted_count = 0;

    for (int i = 0; i < g_hide_dir_count && g_running; i++) {
        const char *path = g_hide_dirs[i];
        struct stat st;
        int path_exists = (stat(path, &st) == 0);

        /* 对于 Android/data/obb FUSE 路径, 如果不存在, 尝试媒体路径 */
        if (!path_exists && (is_android_data_path(path) || is_android_obb_path(path))) {
            char real_path[MAX_PKG_LEN];
            const char *rest = path + 18;
            snprintf(real_path, sizeof(real_path), "/data/media/%s", rest);
            struct stat real_st;
            if (stat(real_path, &real_st) == 0) {
                log_print("[隐藏] FUSE 路径不存在, 使用媒体路径: %s → %s\n", path, real_path);
                if (stat(g_empty_dir, &empty_st) == 0 && S_ISDIR(empty_st.st_mode)) {
                    sync_empty_attrs(&real_st);
                    if (mount(g_empty_dir, real_path, NULL, MS_BIND, NULL) == 0) {
                        log_print("[成功] 隐藏媒体数据: %s (uid=%d gid=%d mode=%o)\n",
                                  real_path, real_st.st_uid, real_st.st_gid, real_st.st_mode & 07777);
                        snprintf(g_mounted_dirs[g_mounted_count++], MAX_PKG_LEN, "%s", real_path);
                    } else {
                        log_print("[失败] 媒体路径 bind: errno=%d (%s)\n", errno, strerror(errno));
                    }
                }
                continue;
            }
        }

        if (!path_exists) {
            log_print("[隐藏] 跳过: %s (不存在)\n", path);
            continue;
        }

        if (S_ISREG(st.st_mode)) {
            /* 文件: bind /dev/null */
            if (mount("/dev/null", path, NULL, MS_BIND, NULL) == 0) {
                log_print("[成功] 隐藏文件 %s\n", path);
                snprintf(g_mounted_dirs[g_mounted_count++], MAX_PKG_LEN, "%s", path);
            } else {
                log_print("[失败] 隐藏文件 %s errno=%d\n", path, errno);
            }
            continue;
        }

        if (!S_ISDIR(st.st_mode)) {
            log_print("[隐藏] 跳过: %s (非文件/目录)\n", path);
            continue;
        }

        /* 目录处理 */
        int mounted = 0;

        if (is_android_data_path(path) || is_android_obb_path(path)) {
            /* === Android/data 或 Android/obb 专用处理 ===
             * FUSE daemon 内部重定向, bind mount 在 FUSE 路径上不生效
             * 需要同时 bind mount 媒体路径 (/data/media/N/...)
             */
            log_print("[隐藏] FUSE 专用: %s\n", path);

            /* 计算媒体路径 */
            char real_path[MAX_PKG_LEN];
            const char *rest = path + 18; /* 跳过 /storage/emulated/ */
            snprintf(real_path, sizeof(real_path), "/data/media/%s", rest);
            log_print("[隐藏] 媒体路径: %s\n", real_path);

            struct stat real_st;
            if (stat(real_path, &real_st) == 0) {
                if (stat(g_empty_dir, &empty_st) == 0 && S_ISDIR(empty_st.st_mode)) {
                    sync_empty_attrs(&real_st);
                    if (mount(g_empty_dir, real_path, NULL, MS_BIND, NULL) == 0) {
                        log_print("[成功] 隐藏媒体数据: %s (uid=%d gid=%d mode=%o)\n",
                                  real_path, real_st.st_uid, real_st.st_gid, real_st.st_mode & 07777);
                        snprintf(g_mounted_dirs[g_mounted_count++], MAX_PKG_LEN, "%s", real_path);
                        mounted = 1;
                    } else {
                        log_print("[失败] 媒体路径 bind: errno=%d (%s)\n", errno, strerror(errno));
                    }
                }
            } else {
                log_print("[隐藏] 媒体路径不存在: %s\n", real_path);
            }

            /* 同时 bind mount FUSE 路径 (双保险) */
            if (stat(g_empty_dir, &empty_st) == 0 && S_ISDIR(empty_st.st_mode)) {
                sync_empty_attrs(&st);
                if (mount(g_empty_dir, path, NULL, MS_BIND, NULL) == 0) {
                    log_print("[成功] 隐藏 FUSE 路径: %s (uid=%d gid=%d mode=%o)\n",
                              path, st.st_uid, st.st_gid, st.st_mode & 07777);
                    snprintf(g_mounted_dirs[g_mounted_count++], MAX_PKG_LEN, "%s", path);
                    mounted = 1;
                }
            }

        } else if (is_storage_path(path)) {
            /* === /storage 下普通路径 (非 Android/data) ===
             * 直接 bind mount, FUSE shared 传播正常
             */
            log_print("[隐藏] 直接 bind: %s\n", path);
            if (stat(g_empty_dir, &empty_st) == 0 && S_ISDIR(empty_st.st_mode)) {
                sync_empty_attrs(&st);
                if (mount(g_empty_dir, path, NULL, MS_BIND, NULL) == 0) {
                    log_print("[成功] 隐藏 %s (uid=%d gid=%d mode=%o)\n",
                              path, st.st_uid, st.st_gid, st.st_mode & 07777);
                    snprintf(g_mounted_dirs[g_mounted_count++], MAX_PKG_LEN, "%s", path);
                    mounted = 1;
                } else {
                    log_print("[失败] bind mount: errno=%d\n", errno);
                }
            }

        } else {
            /* === 普通目录 (非 FUSE) === */
            log_print("[隐藏] 普通目录 bind: %s\n", path);
            if (stat(g_empty_dir, &empty_st) == 0 && S_ISDIR(empty_st.st_mode)) {
                sync_empty_attrs(&st);
                if (mount(g_empty_dir, path, NULL, MS_BIND, NULL) == 0) {
                    log_print("[成功] 隐藏 %s (uid=%d gid=%d mode=%o)\n",
                              path, st.st_uid, st.st_gid, st.st_mode & 07777);
                    snprintf(g_mounted_dirs[g_mounted_count++], MAX_PKG_LEN, "%s", path);
                    mounted = 1;
                } else {
                    log_print("[失败] bind mount: errno=%d\n", errno);
                }
            }
        }

        /* tmpfs 回退 */
        if (!mounted) {
            char opts[256];
            snprintf(opts, sizeof(opts), "size=1k,uid=%d,gid=%d,mode=%o",
                     st.st_uid, st.st_gid, st.st_mode & 07777);
            if (mount("tmpfs", path, "tmpfs", MS_RDONLY, opts) == 0) {
                if (is_storage_path(path))
                    mount(NULL, path, NULL, MS_PRIVATE, NULL);
                log_print("[成功] 隐藏 %s (tmpfs)\n", path);
                snprintf(g_mounted_dirs[g_mounted_count++], MAX_PKG_LEN, "%s", path);
                mounted = 1;
            }
        }

        if (!mounted) {
            log_print("[失败] %s 所有方式均失败\n", path);
        }
    }

    log_print("[隐藏] 完成: %d 个路径已隐藏\n", g_mounted_count);
}

#ifndef MNT_DETACH
#define MNT_DETACH 2
#endif

static void unhide_directories(void)
{
    for (int i = 0; i < g_mounted_count; i++) {
        if (umount2(g_mounted_dirs[i], MNT_DETACH) == 0 ||
            umount2(g_mounted_dirs[i], MNT_FORCE) == 0 ||
            umount(g_mounted_dirs[i]) == 0) {
            log_print("[恢复] %s\n", g_mounted_dirs[i]);
        } else {
            log_print("[警告] 恢复 %s 失败 errno=%d\n", g_mounted_dirs[i], errno);
        }
    }
    g_mounted_count = 0;
    rmdir(g_empty_dir);
}

/* ========== Namespace 管理 ========== */

static int read_ns(const char *path, char *buf, size_t bufsize)
{
    ssize_t len = readlink(path, buf, bufsize - 1);
    if (len < 0) return -1;
    buf[len] = '\0';
    return (int)len;
}

static int enter_init_namespace(void)
{
    int fd = open("/proc/1/ns/mnt", O_RDONLY);
    if (fd < 0) {
        log_print("[命名空间] 无法打开 /proc/1/ns/mnt: %s (errno=%d)\n",
                  strerror(errno), errno);
        return -1;
    }
    if (setns(fd, CLONE_NEWNS) != 0) {
        log_print("[命名空间] setns 失败: %s (errno=%d)\n",
                  strerror(errno), errno);
        close(fd);
        return -1;
    }
    close(fd);
    return 0;
}

/* ========== 信号处理 ========== */

static void signal_handler(int sig)
{
    (void)sig;
    g_running = 0;
}

static void cleanup_pid_file(void)
{
    char pid_file[512];
    strncpy(pid_file, g_config_path, sizeof(pid_file) - 1);
    pid_file[sizeof(pid_file) - 1] = '\0';
    char *slash = strrchr(pid_file, '/');
    if (slash) {
        strcpy(slash + 1, "logd.pid");
    } else {
        snprintf(pid_file, sizeof(pid_file), "/data/local/tmp/Guard/logd.pid");
    }
    unlink(pid_file);
}

static void crash_handler(int sig)
{
    log_print("[崩溃] 收到信号 %d, 正在清理...\n", sig);
    fflush(stderr);
    /* 尝试清理 guard namespace 中的挂载 */
    unhide_directories();
    cleanup_pid_file();
    signal(sig, SIG_DFL);
    raise(sig);
}

/* ========== 主函数 ========== */

int main(int argc, char *argv[])
{
    /* --cleanup 模式: 进入 init namespace, umount 所有 bind mounts */
    if (argc >= 2 && strcmp(argv[1], "--cleanup") == 0) {
        if (argc >= 3 && argv[2][0])
            snprintf(g_config_path, sizeof(g_config_path), "%s", argv[2]);
        else
            snprintf(g_config_path, sizeof(g_config_path), "%s",
                     "/data/local/tmp/Guard/config.txt");

        set_process_name(argc, argv, 0); /* cleanup: 固定名 logd, 不写 PID */

        init_empty_dir();

        memset(g_hide_dirs, 0, sizeof(g_hide_dirs));
        g_hide_dir_count = 0;

        load_config();

        log_print("[cleanup] 配置: %d 个路径\n", g_hide_dir_count);

        if (enter_init_namespace() != 0) {
            log_print("[cleanup] 无法进入 init namespace\n");
            return 1;
        }

        log_print("[cleanup] 开始清理 bind mounts...\n");
        int cleaned = 0;
        for (int i = 0; i < g_hide_dir_count; i++) {
            const char *path = g_hide_dirs[i];
            int umounted = 0;

            /* 多次 umount (可能有多层 bind) */
            for (int attempt = 0; attempt < 3; attempt++) {
                if (umount2(path, MNT_DETACH) == 0) {
                    log_print("[cleanup] umount: %s\n", path);
                    umounted = 1;
                } else {
                    break;
                }
            }

            /* Android/data/obb: 也清理媒体路径 */
            if (is_android_data_path(path) || is_android_obb_path(path)) {
                char real_path[MAX_PKG_LEN];
                const char *rest = path + 18;
                snprintf(real_path, sizeof(real_path), "/data/media/%s", rest);
                for (int attempt = 0; attempt < 3; attempt++) {
                    if (umount2(real_path, MNT_DETACH) == 0) {
                        log_print("[cleanup] umount real: %s\n", real_path);
                        umounted = 1;
                    } else {
                        break;
                    }
                }
            }

            if (umounted) cleaned++;
        }

        /* 清理临时空目录 */
        rmdir(g_empty_dir);

        log_print("[cleanup] 完成, 清理了 %d 个路径\n", cleaned);
        return 0;
    }

    /* 正常模式 */
    if (argc < 2 || !argv[1] || !argv[1][0]) {
        snprintf(g_config_path, sizeof(g_config_path), "%s",
                 "/data/local/tmp/Guard/config.txt");
    } else {
        snprintf(g_config_path, sizeof(g_config_path), "%s", argv[1]);
    }

    set_process_name(argc, argv, 1); /* 正常模式: 固定名 logd, 写 PID 文件 */

    signal(SIGINT,  signal_handler);
    signal(SIGTERM, signal_handler);
    signal(SIGHUP,  signal_handler);
    signal(SIGPIPE, SIG_IGN);

    memset(g_hide_dirs, 0, sizeof(g_hide_dirs));
    g_hide_dir_count = 0;

    /* 检查/创建配置文件 */
    struct stat st;
    if (stat(g_config_path, &st) != 0) {
        log_print("[错误] 配置文件不存在: %s\n", g_config_path);
        return 1;
    }

    log_print("[启动] 配置: %s\n", g_config_path);

    init_empty_dir();

    if (load_config() != 0) {
        return 1;
    }

    g_is_root = (getuid() == 0);

    if (!g_is_root) {
        log_print("[错误] 需要 root 权限\n");
        return 1;
    }

    if (g_hide_dir_count == 0) {
        log_print("[警告] 没有隐藏路径\n");
        return 0;
    }

    /* === 存储隔离流程 === */
    char host_ns[256];
    if (read_ns("/proc/self/ns/mnt", host_ns, sizeof(host_ns)) > 0) {
        log_print("[命名空间] 宿主 mount namespace: %s\n", host_ns);
    }

    if (hide_list_has_storage_paths()) {
        /* === 单目录级隔离模式 ===
         * 1. 进入 init namespace (su 启动时在 app namespace)
         * 2. 在 init namespace 中 bind mount 隐藏目录 (所有 app 看到空)
         * 3. 创建新 mount namespace
         * 4. 切断传播 (MS_REC|MS_PRIVATE)
         * 5. 在 guard namespace 中 umount bind (恢复, guard 恢复可见)
         * 6. guard namespace 中所有路径恢复正常 (无需额外 bind)
         */
        log_print("[存储隔离] 模式: 单目录级隔离\n");

        /* 1. 进入 init namespace */
        log_print("[命名空间] 进入 init mount namespace...\n");
        if (enter_init_namespace() != 0) {
            log_print("[错误] 无法进入 init namespace\n");
            return 1;
        }
        char init_ns[256];
        if (read_ns("/proc/self/ns/mnt", init_ns, sizeof(init_ns)) > 0) {
            log_print("[命名空间] 当前 (init) namespace: %s\n", init_ns);
        }

        /* 2. 清理上次残留 */
        log_print("[清理] 清理上次残留的 bind mounts...\n");
        for (int i = 0; i < g_hide_dir_count; i++) {
            const char *path = g_hide_dirs[i];
            umount2(path, MNT_DETACH);
            umount2(path, MNT_DETACH);
            if (is_android_data_path(path) || is_android_obb_path(path)) {
                char real_path[MAX_PKG_LEN];
                const char *rest = path + 18;
                snprintf(real_path, sizeof(real_path), "/data/media/%s", rest);
                umount2(real_path, MNT_DETACH);
            }
        }

        /* 3. 在 init namespace 中隐藏目录 */
        hide_directories();

        /* 4. 创建独立 mount namespace */
        log_print("[命名空间] 创建独立 mount namespace...\n");
        if (unshare(CLONE_NEWNS) != 0) {
            log_print("[错误] unshare 失败: errno=%d (%s)\n", errno, strerror(errno));
            return 1;
        }

        char guard_ns[256];
        if (read_ns("/proc/self/ns/mnt", guard_ns, sizeof(guard_ns)) > 0) {
            log_print("[命名空间] Guard namespace: %s\n", guard_ns);
        }

        /* 5. 切断传播 */
        if (mount(NULL, "/", NULL, MS_REC | MS_PRIVATE, NULL) != 0) {
            log_print("[命名空间] 警告: MS_PRIVATE 失败 errno=%d\n", errno);
        }
        log_print("[命名空间] 隔离成功\n");

        /* 6. 在 guard namespace 中恢复 (umount bind mounts) */
        log_print("[恢复] 在 guard namespace 中恢复目录可见性\n");
        for (int i = 0; i < g_mounted_count; i++) {
            if (umount2(g_mounted_dirs[i], MNT_DETACH) == 0) {
                log_print("[恢复] guard 可见: %s\n", g_mounted_dirs[i]);
            }
        }

    } else {
        /* === 普通目录模式: 进入 init namespace 后 bind mount ===
         * su 启动时在 app namespace, 需要进入 init namespace
         * 才能让 bind mount 对所有进程生效
         */
        log_print("[隔离] 模式: 普通目录 bind mount\n");

        /* 1. 进入 init namespace */
        log_print("[命名空间] 进入 init mount namespace...\n");
        if (enter_init_namespace() != 0) {
            log_print("[错误] 无法进入 init namespace\n");
            return 1;
        }
        char init_ns[256];
        if (read_ns("/proc/self/ns/mnt", init_ns, sizeof(init_ns)) > 0) {
            log_print("[命名空间] 当前 (init) namespace: %s\n", init_ns);
        }

        /* 2. 清理上次残留 */
        log_print("[清理] 清理上次残留的 bind mounts...\n");
        for (int i = 0; i < g_hide_dir_count; i++) {
            umount2(g_hide_dirs[i], MNT_DETACH);
            umount2(g_hide_dirs[i], MNT_DETACH);
        }

        /* 3. 在 init namespace 中隐藏目录 */
        hide_directories();

        /* 4. 不创建新 namespace, guard 留在 init namespace */
    }

    /* 崩溃信号处理 */
    struct sigaction sa;
    memset(&sa, 0, sizeof(sa));
    sa.sa_handler = crash_handler;
    sigemptyset(&sa.sa_mask);
    sa.sa_flags = 0;
    sigaction(SIGSEGV, &sa, NULL);
    sigaction(SIGABRT, &sa, NULL);
    sigaction(SIGBUS,  &sa, NULL);
    sigaction(SIGILL,  &sa, NULL);
    sigaction(SIGFPE,  &sa, NULL);

    /* 最终确认 */
    char final_ns[256];
    if (read_ns("/proc/self/ns/mnt", final_ns, sizeof(final_ns)) > 0) {
        log_print("[命名空间] 最终: %s\n", final_ns);
    }

    log_print("[系统] Guard 已启动, PID=%d\n", getpid());
    log_print("[系统] 隐藏状态: %d 个路径\n", g_mounted_count);
    for (int i = 0; i < g_mounted_count; i++) {
        log_print("[系统]   [%d] %s\n", i, g_mounted_dirs[i]);
    }
    log_print("[系统] 等待信号退出...\n");

    while (g_running) {
        sleep(1);
    }

    /* 退出清理 */
    log_print("[退出] Guard 正在停止\n");
    unhide_directories();
    cleanup_pid_file();
    log_print("[退出] Guard 已停止\n");
    return 0;
}
