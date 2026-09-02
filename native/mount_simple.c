/*
 * mount_simple.c - 普通目录 bind mount 隐藏
 *
 * 职责:
 *   - 对普通目录 (非 FUSE 路径) 执行 bind mount 隐藏/恢复
 *   - 不涉及 mount namespace
 *   - 不涉及 Android FUSE / vold
 *
 * 适用路径:
 *   /data/local/tmp/xxx
 *   /data/xxx
 *   /cache/xxx
 *   等非 /storage/emulated 路径
 *
 * 不适用:
 *   /storage/emulated/0/Android/data/com.xxx (使用 mount_android_data.c)
 *
 * 原理:
 *   mount(source, target, NULL, MS_BIND, NULL)
 *   将空目录 bind mount 到目标路径上, 覆盖原有内容
 *   umount2(target, MNT_DETACH) 恢复
 */

#include "logd.h"

/* ========== 子挂载处理 ========== */

#define SIMPLE_MAX_SUBMOUNTS  128
#define SIMPLE_MAX_MOUNT_PATH 512

struct simple_submount {
    char mount_point[SIMPLE_MAX_MOUNT_PATH];
    int  depth;
};

static int simple_cmp_depth_asc(const void *a, const void *b)
{
    const struct simple_submount *ea = (const struct simple_submount *)a;
    const struct simple_submount *eb = (const struct simple_submount *)b;
    return ea->depth - eb->depth;
}

static int simple_cmp_depth_desc(const void *a, const void *b)
{
    const struct simple_submount *ea = (const struct simple_submount *)a;
    const struct simple_submount *eb = (const struct simple_submount *)b;
    return eb->depth - ea->depth;
}

static int simple_find_submounts(const char *target,
                                 struct simple_submount *entries, int max_entries)
{
    FILE *f = fopen("/proc/self/mountinfo", "r");
    if (!f) return -1;

    int count = 0;
    char line[4096];
    int target_len = strlen(target);

    while (fgets(line, sizeof(line), f) && count < max_entries) {
        int mnt_id, pnt_id;
        char dev[32], root[SIMPLE_MAX_MOUNT_PATH], mount_point[SIMPLE_MAX_MOUNT_PATH];

        if (sscanf(line, "%d %d %31s %511s %511s",
                   &mnt_id, &pnt_id, dev, root, mount_point) != 5)
            continue;

        if (strcmp(mount_point, target) == 0)
            continue;

        if (strncmp(mount_point, target, target_len) != 0)
            continue;
        if (mount_point[target_len] != '/')
            continue;

        int depth = 0;
        for (int i = 0; mount_point[i]; i++) {
            if (mount_point[i] == '/') depth++;
        }

        strncpy(entries[count].mount_point, mount_point, SIMPLE_MAX_MOUNT_PATH - 1);
        entries[count].mount_point[SIMPLE_MAX_MOUNT_PATH - 1] = '\0';
        entries[count].depth = depth;
        count++;
    }

    fclose(f);
    return count;
}

/*
 * 用空目录覆盖所有子挂载（从浅到深）
 * 返回: 成功覆盖的数量
 */
static int simple_cover_submounts(const char *source, const char *target)
{
    struct simple_submount entries[SIMPLE_MAX_SUBMOUNTS];
    int count = simple_find_submounts(target, entries, SIMPLE_MAX_SUBMOUNTS);
    if (count <= 0) return 0;

    log_print("[simple] 发现 %d 个子挂载, 逐一覆盖...\n", count);

    qsort(entries, count, sizeof(struct simple_submount), simple_cmp_depth_asc);

    int covered = 0;
    for (int i = 0; i < count; i++) {
        const char *mp = entries[i].mount_point;
        struct stat st;
        if (stat(mp, &st) != 0 || !S_ISDIR(st.st_mode))
            continue;

        if (mount(source, mp, NULL, MS_BIND, NULL) == 0) {
            log_print("[simple]   覆盖子挂载: %s\n", mp);
            covered++;
        }
    }

    if (covered > 0)
        log_print("[simple] 已覆盖 %d/%d 个子挂载\n", covered, count);
    return covered;
}

/*
 * 清理子挂载上的 bind mount（从深到浅）
 * 返回: 成功卸载的数量
 */
static int simple_cleanup_submounts(const char *target)
{
    struct simple_submount entries[SIMPLE_MAX_SUBMOUNTS];
    int count = simple_find_submounts(target, entries, SIMPLE_MAX_SUBMOUNTS);
    if (count <= 0) return 0;

    qsort(entries, count, sizeof(struct simple_submount), simple_cmp_depth_desc);

    int cleaned = 0;
    for (int i = 0; i < count; i++) {
        const char *mp = entries[i].mount_point;
        /* 尝试卸载多层 bind */
        for (int attempt = 0; attempt < 3; attempt++) {
            if (umount2(mp, MNT_DETACH) == 0)
                cleaned++;
            else
                break;
        }
    }
    return cleaned;
}

/*
 * 判断路径是否为 Android/data 路径
 *
 * /storage/emulated/0/Android/data/ 开头的路径返回 1
 * 其他路径返回 0
 */
int is_android_data_path(const char *path)
{
    return strncmp(path, ANDROID_DATA_PREFIX,
                   strlen(ANDROID_DATA_PREFIX)) == 0;
}

/*
 * 普通目录 bind mount 隐藏
 *
 * 直接将 source (空目录) bind mount 到 target
 * 不需要 namespace, 不涉及 FUSE
 *
 * 参数:
 *   source - 空目录路径 (通常为 EMPTY_DIR)
 *   target - 要隐藏的目标目录
 *
 * 返回: 0=成功, -1=失败
 */
int simple_bind_hide(const char *source, const char *target)
{
    struct stat st;

    /* 安全检查: 不要对 Android/data 路径使用普通 bind */
    if (is_android_data_path(target)) {
        log_print("[simple] 错误: %s 是 Android/data 路径, 请使用 android_data_namespace_mount()\n",
                  target);
        return -1;
    }

    /* 检查目标是否存在 */
    if (stat(target, &st) != 0) {
        log_print("[simple] 错误: 目标不存在: %s (%s)\n",
                  target, strerror(errno));
        return -1;
    }

    /* 检查是否是目录 */
    if (!S_ISDIR(st.st_mode)) {
        log_print("[simple] 错误: 目标不是目录: %s\n", target);
        return -1;
    }

    /* 检查源目录是否存在 */
    if (stat(source, &st) != 0) {
        log_print("[simple] 错误: 源目录不存在: %s\n", source);
        return -1;
    }

    /* 先覆盖子挂载（防止子挂载穿透 bind mount 显示）
     * 例如 /data/adb 下的 KSU 模块挂载点 */
    simple_cover_submounts(source, target);

    /* 执行 bind mount */
    log_print("[simple] bind mount: %s -> %s\n", source, target);

    if (mount(source, target, NULL, MS_BIND, NULL) != 0) {
        log_print("[simple] 错误: bind mount 失败: errno=%d (%s)\n",
                  errno, strerror(errno));
        return -1;
    }

    /* 设置只读 */
    if (mount(NULL, target, NULL, MS_BIND | MS_REMOUNT | MS_RDONLY, NULL) != 0) {
        log_print("[simple] 警告: 设置只读失败: errno=%d, 继续\n", errno);
    }

    log_print("[simple] 成功: %s 已隐藏\n", target);
    return 0;
}

/*
 * 普通目录 bind mount 恢复
 *
 * umount2 MNT_DETACH 卸载 bind mount
 *
 * 参数:
 *   target - 已隐藏的目标目录
 *
 * 返回: 0=成功, -1=失败
 */
int simple_bind_unhide(const char *target)
{
    /* 安全检查 */
    if (is_android_data_path(target)) {
        log_print("[simple] 错误: %s 是 Android/data 路径, 请使用 android_data_namespace_unmount()\n",
                  target);
        return -1;
    }

    log_print("[simple] umount: %s\n", target);

    /* 先清理子挂载上的 bind mount（从深到浅） */
    simple_cleanup_submounts(target);

    if (umount2(target, MNT_DETACH) != 0) {
        log_print("[simple] 错误: umount 失败: errno=%d (%s)\n",
                  errno, strerror(errno));
        return -1;
    }

    log_print("[simple] 成功: %s 已恢复\n", target);
    return 0;
}
