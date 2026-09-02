/*
 * mount_android_data.c - Android/data 专用 Namespace 隔离
 *
 * 职责:
 *   - 对 /storage/emulated/0/Android/data/com.xxx 路径执行隔离
 *   - 使用独立 mount namespace
 *   - 处理 FUSE shared mount 传播问题
 *   - 不使用普通 bind mount 方法
 *
 * 适用路径:
 *   /storage/emulated/0/Android/data/com.xxx
 *
 * 不适用:
 *   普通目录 (使用 mount_simple.c)
 *
 * === 原理 ===
 *
 * Android FUSE 挂载是 shared mount (例如 shared:87)
 * 直接 bind mount 会导致 propagation 污染 /mnt/user 等路径
 *
 * 正确流程 (三步隔离法):
 *
 * 步骤 1: 在 init namespace 中创建隔离层
 *   a. self-bind: mount --bind path path
 *      → 在 FUSE 上创建一个新 mount point
 *      → 会传播到 /mnt/user (但是 self-bind, 内容不变, 无害)
 *   b. make-private: mount --make-private path
 *      → 切断该 mount point 的传播
 *      → 后续 bind mount 不会传播到 /mnt/user
 *   c. bind-empty: mount --bind empty_dir path
 *      → 覆盖目标为空目录
 *      → 因为 step b 已设 private, 不会传播
 *
 *   效果: init namespace 中该路径显示空目录
 *         /mnt/user 中有一个无害的 self-bind (内容不变)
 *         FUSE 会话不受影响
 *         vold 不受影响
 *
 * 步骤 2: 创建独立 namespace
 *   unshare(CLONE_NEWNS)
 *   → 新 namespace 继承 init 中的所有 mount (包括 bind-empty)
 *   → MS_REC|MS_PRIVATE 切断传播
 *
 * 步骤 3: 在 logd namespace 中恢复
 *   umount2(path, MNT_DETACH)
 *   → 移除 bind-empty, 露出 self-bind (真实内容)
 *   → 因为 MS_PRIVATE, 不影响 init namespace
 *
 * 最终效果:
 *   - 其他 namespace (init): 看到空目录 ✓
 *   - logd namespace: 看到真实内容 ✓
 *   - FUSE 会话: 不受影响 ✓
 *   - /mnt/user: 无害的 self-bind ✓
 *   - vold: 不受影响 ✓
 */

#include "logd.h"

/*
 * 在 init namespace 中创建隔离层
 *
 * 执行 self-bind → make-private → bind-empty
 *
 * 参数:
 *   package_path - /storage/emulated/0/Android/data/com.xxx
 *
 * 返回: 0=成功, -1=失败
 */
static int create_isolation_layer(const char *package_path)
{
    struct stat st;

    log_print("[Android/data] === 步骤 1: 在 init namespace 中创建隔离层 ===\n");

    /* 检查路径存在 */
    if (stat(package_path, &st) != 0) {
        log_print("[Android/data] 错误: 路径不存在: %s (%s)\n",
                  package_path, strerror(errno));
        return -1;
    }

    if (!S_ISDIR(st.st_mode)) {
        log_print("[Android/data] 错误: 不是目录: %s\n", package_path);
        return -1;
    }

    /* --- 1a. self-bind: 创建 mount point --- */
    log_print("[Android/data] 1a. self-bind: %s → %s\n",
              package_path, package_path);

    if (mount(package_path, package_path, NULL, MS_BIND, NULL) != 0) {
        log_print("[Android/data] 1a 失败: errno=%d (%s)\n",
                  errno, strerror(errno));
        /* 可能已经有 bind mount (上次崩溃残留), 尝试继续 */
        log_print("[Android/data] 1a: 可能已有 mount, 继续\n");
    } else {
        log_print("[Android/data] 1a 成功: mount point 已创建\n");
    }

    /* --- 1b. make-private: 切断传播 --- */
    log_print("[Android/data] 1b. make-private: %s\n", package_path);

    if (mount(NULL, package_path, NULL, MS_PRIVATE, NULL) != 0) {
        log_print("[Android/data] 1b 失败: errno=%d (%s), 继续\n",
                  errno, strerror(errno));
        /* 即使失败也继续, 后面的 bind 仍可工作 */
    } else {
        log_print("[Android/data] 1b 成功: 传播已切断\n");
    }

    /* --- 1c. bind-empty: 覆盖为空目录 --- */
    ensure_empty_dir();

    log_print("[Android/data] 1c. bind-empty: %s → %s\n",
              EMPTY_DIR, package_path);

    if (mount(EMPTY_DIR, package_path, NULL, MS_BIND, NULL) != 0) {
        log_print("[Android/data] 1c 失败: errno=%d (%s)\n",
                  errno, strerror(errno));
        return -1;
    }

    /* 设置只读 + 保持权限 */
    mount(NULL, package_path, NULL,
          MS_BIND | MS_REMOUNT | MS_RDONLY, NULL);

    log_print("[Android/data] 1c 成功: 已覆盖为空目录\n");
    log_print("[Android/data] 步骤 1 完成: init namespace 中 %s → 空目录\n",
              package_path);

    return 0;
}

/*
 * 在 logd namespace 中恢复真实内容
 *
 * umount2 移除 bind-empty, 露出 self-bind (真实内容)
 * 因为 MS_PRIVATE, 不影响 init namespace
 *
 * 参数:
 *   package_path - /storage/emulated/0/Android/data/com.xxx
 *
 * 返回: 0=成功, -1=失败
 */
int android_data_reveal_in_namespace(const char *package_path)
{
    log_print("[Android/data] === 步骤 3: 在 logd namespace 中恢复 ===\n");

    /*
     * umount bind-empty
     * 移除最上层的 bind-empty mount
     * 露出下层的 self-bind (显示真实内容)
     */
    log_print("[Android/data] 3. umount bind-empty: %s\n", package_path);

    if (umount2(package_path, MNT_DETACH) != 0) {
        log_print("[Android/data] 3 失败: errno=%d (%s)\n",
                  errno, strerror(errno));
        /* 可能已经 umount, 尝试再 umount 一次 (移除 self-bind) */
        if (umount2(package_path, MNT_DETACH) != 0) {
            log_print("[Android/data] 3: 二次 umount 也失败, 继续\n");
        }
    }

    log_print("[Android/data] 3 成功: logd namespace 中 %s → 真实内容\n",
              package_path);

    /* 验证: 列出目录内容 */
    DIR *d = opendir(package_path);
    if (d) {
        int count = 0;
        struct dirent *e;
        while ((e = readdir(d)) != NULL) {
            if (strcmp(e->d_name, ".") != 0 && strcmp(e->d_name, "..") != 0) {
                count++;
            }
        }
        closedir(d);
        log_print("[Android/data] 验证: %s 中有 %d 个条目\n",
                  package_path, count);
        if (count > 0) {
            log_print("[Android/data] 验证: logd namespace 可见真实内容 ✓\n");
        } else {
            log_print("[Android/data] 警告: 目录为空, 可能未恢复\n");
        }
    } else {
        log_print("[Android/data] 警告: 无法打开 %s: %s\n",
                  package_path, strerror(errno));
    }

    return 0;
}

/*
 * Android/data 专用 namespace 隔离 (完整流程)
 *
 * 参数:
 *   package_path - /storage/emulated/0/Android/data/com.xxx
 *
 * 返回: 0=成功, -1=失败
 */
int android_data_namespace_mount(const char *package_path)
{
    char host_ns[256];
    char logd_ns[256];

    /* 安全检查: 必须是 Android/data 路径 */
    if (!is_android_data_path(package_path)) {
        log_print("[Android/data] 错误: 不是 Android/data 路径: %s\n",
                  package_path);
        return -1;
    }

    log_print("[Android/data] ========================================\n");
    log_print("[Android/data] 开始隔离: %s\n", package_path);
    log_print("[Android/data] ========================================\n");

    /* 记录 init namespace */
    if (get_namespace_id(host_ns, sizeof(host_ns)) != 0) {
        log_print("[Android/data] 错误: 无法获取 init namespace ID\n");
        return -1;
    }
    log_print("[Android/data] init namespace: %s\n", host_ns);

    /* === 步骤 1: 在 init namespace 中创建隔离层 === */
    if (create_isolation_layer(package_path) != 0) {
        log_print("[Android/data] 错误: 隔离层创建失败\n");
        return -1;
    }

    /* === 步骤 2: 创建独立 namespace === */
    log_print("[Android/data] === 步骤 2: 创建独立 mount namespace ===\n");

    if (create_mount_namespace() != 0) {
        log_print("[Android/data] 错误: unshare 失败\n");
        log_print("[Android/data] 警告: init namespace 中已有 bind mounts, 需要手动清理\n");
        return -1;
    }

    if (get_namespace_id(logd_ns, sizeof(logd_ns)) != 0) {
        log_print("[Android/data] 错误: 无法获取 logd namespace ID\n");
        return -1;
    }
    log_print("[Android/data] logd namespace: %s\n", logd_ns);

    if (!verify_namespace_changed(host_ns, logd_ns)) {
        log_print("[Android/data] 错误: namespace 未变化, 退出\n");
        return -1;
    }

    /* 切断传播 (新 namespace 中的修改不影响 init) */
    make_mounts_private();

    log_print("[Android/data] 步骤 2 完成: namespace 已隔离\n");

    /* === 步骤 3: 在 logd namespace 中恢复真实内容 === */
    if (android_data_reveal_in_namespace(package_path) != 0) {
        log_print("[Android/data] 警告: 恢复失败, 但隔离已生效\n");
    }

    log_print("[Android/data] ========================================\n");
    log_print("[Android/data] 隔离完成:\n");
    log_print("[Android/data]   init namespace (其他 app): %s → 空目录\n", package_path);
    log_print("[Android/data]   logd namespace (本进程):  %s → 真实内容\n", package_path);
    log_print("[Android/data] ========================================\n");

    return 0;
}

/*
 * Android/data namespace 恢复 (清理 init namespace 中的 bind mounts)
 *
 * 在 init namespace 中执行 (从外部调用)
 * 顺序: 先 umount bind-empty, 再 umount self-bind
 *
 * 参数:
 *   package_path - /storage/emulated/0/Android/data/com.xxx
 *
 * 返回: 0=成功, -1=失败
 */
int android_data_namespace_unmount(const char *package_path)
{
    log_print("[Android/data] 恢复: 清理 %s 的 bind mounts\n", package_path);

    /* 从最上层开始 umount (bind-empty 先, self-bind 后) */
    int attempts = 0;
    while (attempts < 3) {
        if (umount2(package_path, MNT_DETACH) != 0) {
            break;  /* 没有更多 mount 可卸载 */
        }
        log_print("[Android/data] umount 成功 (第 %d 次)\n", attempts + 1);
        attempts++;
    }

    if (attempts > 0) {
        log_print("[Android/data] 恢复成功: 清理了 %d 层 mount\n", attempts);
        return 0;
    }

    log_print("[Android/data] 无需清理 (没有残留 mount)\n");
    return 0;
}
