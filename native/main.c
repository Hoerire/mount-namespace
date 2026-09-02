/*
 * main.c - 入口文件, 路由两种隔离逻辑
 *
 * 职责:
 *   - 解析命令行参数
 *   - 根据路径类型路由到正确的处理逻辑
 *   - 不直接处理 mount 操作
 *
 * 两种逻辑严格分离:
 *   1. 普通目录 → simple_bind_hide()       (mount_simple.c)
 *   2. Android/data → android_data_namespace_mount() (mount_android_data.c)
 *
 * 用法:
 *   logd --hide <path>           隐藏目录 (自动判断类型)
 *   logd --unhide <path>         恢复目录
 *   logd --hide-android-data <path>  仅处理 Android/data
 *   logd --hide-simple <path>    仅处理普通目录
 *   logd --list                  列出当前 mount 中的隐藏项
 *   logd --test                  运行自测
 *
 * 示例:
 *   # 普通目录
 *   logd --hide /data/local/tmp/test
 *
 *   # Android/data (自动路由到 namespace 方案)
 *   logd --hide /storage/emulated/0/Android/data/com.omarea.vtools
 *
 *   # 混合: 多个路径
 *   logd --hide /data/local/tmp/test --hide /storage/emulated/0/Android/data/com.xxx
 */

#include "logd.h"

/* 已隐藏的路径列表 (用于恢复) */
static char g_hidden_paths[MAX_HIDES][MAX_PATH_LEN];
static int  g_hidden_count = 0;

/*
 * 自动路由: 根据路径类型选择处理方式
 *
 * Android/data 路径 → android_data_namespace_mount()
 * 其他路径 → simple_bind_hide()
 *
 * 返回: 0=成功, -1=失败
 */
static int auto_hide(const char *path)
{
    if (is_android_data_path(path)) {
        log_print("[main] 路由: %s → Android/data 专用逻辑\n", path);
        return android_data_namespace_mount(path);
    } else {
        log_print("[main] 路由: %s → 普通目录 bind\n", path);
        return simple_bind_hide(EMPTY_DIR, path);
    }
}

/*
 * 自动路由: 恢复
 */
static int auto_unhide(const char *path)
{
    if (is_android_data_path(path)) {
        log_print("[main] 路由: %s → Android/data 恢复\n", path);
        return android_data_namespace_unmount(path);
    } else {
        log_print("[main] 路由: %s → 普通目录恢复\n", path);
        return simple_bind_unhide(path);
    }
}

/*
 * 列出 /proc/self/mountinfo 中与隐藏相关的条目
 */
static void list_mounts(void)
{
    FILE *f = fopen("/proc/self/mountinfo", "r");
    if (!f) {
        log_print("[main] 错误: 无法打开 /proc/self/mountinfo\n");
        return;
    }

    char line[4096];
    int count = 0;

    log_print("[main] === 当前 mount entries ===\n");

    while (fgets(line, sizeof(line), f)) {
        /* 只显示包含 logd 或 .logd 的条目 */
        if (strstr(line, "logd") || strstr(line, ".logd")) {
            /* 提取 mount point 字段 */
            int mnt_id, pnt_id;
            char dev[32], root[MAX_PATH_LEN], mnt[MAX_PATH_LEN];
            if (sscanf(line, "%d %d %31s %511s %511s",
                       &mnt_id, &pnt_id, dev, root, mnt) == 5) {
                log_print("  [%d] %s\n", mnt_id, mnt);
                count++;
            }
        }
    }

    fclose(f);
    log_print("[main] 共 %d 个隐藏相关条目\n", count);
}

/*
 * 自测: 验证基本功能
 */
static int run_tests(void)
{
    log_print("[test] === 自测开始 ===\n");

    int pass = 0, fail = 0;

    /* 测试 1: is_android_data_path */
    log_print("[test] 1. is_android_data_path()\n");
    if (is_android_data_path("/storage/emulated/0/Android/data/com.test")) {
        log_print("[test]   ✓ 正确识别 Android/data 路径\n");
        pass++;
    } else {
        log_print("[test]   ✗ 未能识别 Android/data 路径\n");
        fail++;
    }

    if (!is_android_data_path("/data/local/tmp/test")) {
        log_print("[test]   ✓ 正确排除非 Android/data 路径\n");
        pass++;
    } else {
        log_print("[test]   ✗ 错误识别非 Android/data 路径\n");
        fail++;
    }

    /* 测试 2: namespace 可用性 */
    log_print("[test] 2. namespace 可用性\n");
    char ns_before[256];
    if (get_namespace_id(ns_before, sizeof(ns_before)) == 0) {
        log_print("[test]   ✓ 当前 namespace: %s\n", ns_before);
        pass++;
    } else {
        log_print("[test]   ✗ 无法获取 namespace ID\n");
        fail++;
    }

    /* 测试 3: empty dir 可创建 */
    log_print("[test] 3. empty dir\n");
    ensure_empty_dir();
    struct stat st;
    if (stat(EMPTY_DIR, &st) == 0 && S_ISDIR(st.st_mode)) {
        log_print("[test]   ✓ 空目录存在: %s\n", EMPTY_DIR);
        pass++;
    } else {
        log_print("[test]   ✗ 空目录创建失败\n");
        fail++;
    }

    /* 测试 4: simple_bind (普通目录) */
    log_print("[test] 4. simple_bind (普通目录)\n");
    const char *test_dir = "/data/local/tmp/.logd_test_dir";
    mkdir(test_dir, 0755);

    /* 创建测试文件 */
    FILE *tf = fopen("/data/local/tmp/.logd_test_dir/test.txt", "w");
    if (tf) {
        fprintf(tf, "test content\n");
        fclose(tf);
    }

    if (simple_bind_hide(EMPTY_DIR, test_dir) == 0) {
        /* 验证: 目录应为空 */
        DIR *d = opendir(test_dir);
        if (d) {
            int empty = 1;
            struct dirent *e;
            while ((e = readdir(d)) != NULL) {
                if (strcmp(e->d_name, ".") != 0 && strcmp(e->d_name, "..") != 0) {
                    empty = 0;
                    break;
                }
            }
            closedir(d);
            if (empty) {
                log_print("[test]   ✓ 隐藏后目录为空\n");
                pass++;
            } else {
                log_print("[test]   ✗ 隐藏后目录非空\n");
                fail++;
            }
        }

        /* 恢复 */
        simple_bind_unhide(test_dir);

        /* 验证: 目录应有内容 */
        if (stat("/data/local/tmp/.logd_test_dir/test.txt", &st) == 0) {
            log_print("[test]   ✓ 恢复后内容可见\n");
            pass++;
        } else {
            log_print("[test]   ✗ 恢复后内容不可见\n");
            fail++;
        }
    } else {
        log_print("[test]   ✗ simple_bind_hide 失败\n");
        fail++;
    }

    /* 清理 */
    unlink("/data/local/tmp/.logd_test_dir/test.txt");
    rmdir(test_dir);

    log_print("[test] === 自测结果: %d 通过, %d 失败 ===\n", pass, fail);
    return fail == 0 ? 0 : 1;
}

/*
 * 打印用法
 */
static void print_usage(void)
{
    fprintf(stderr,
        "用法: logd [选项] <path>\n"
        "\n"
        "选项:\n"
        "  --hide <path>              隐藏目录 (自动判断类型)\n"
        "  --unhide <path>            恢复目录 (自动判断类型)\n"
        "  --hide-simple <path>       仅普通目录 bind mount\n"
        "  --hide-android-data <path>  仅 Android/data namespace 隔离\n"
        "  --list                     列出隐藏相关 mount\n"
        "  --test                     运行自测\n"
        "  --idle                     保持进程存活 (配合 namespace 使用)\n"
        "\n"
        "路径类型自动路由:\n"
        "  /storage/emulated/0/Android/data/* → namespace 方案\n"
        "  其他路径                           → 普通 bind mount\n"
    );
}

/*
 * --idle 模式: 保持进程存活
 * 用于 namespace 隔离后, 保持 logd 进程在独立 namespace 中运行
 */
static int idle_mode(void)
{
    log_print("[idle] 进程保持存活, PID=%d\n", getpid());
    log_print("[idle] namespace 已隔离, 等待外部信号\n");

    /* 等待信号 */
    while (1) {
        pause();
    }

    return 0;
}

int main(int argc, char *argv[])
{
    log_print("[main] logd 启动, PID=%d\n", getpid());

    if (argc < 2) {
        print_usage();
        return 1;
    }

    /* 解析参数 */
    for (int i = 1; i < argc; i++) {
        if (strcmp(argv[i], "--hide") == 0 && i + 1 < argc) {
            const char *path = argv[++i];
            if (auto_hide(path) == 0) {
                snprintf(g_hidden_paths[g_hidden_count++], MAX_PATH_LEN, "%s", path);
            }
        }
        else if (strcmp(argv[i], "--unhide") == 0 && i + 1 < argc) {
            const char *path = argv[++i];
            auto_unhide(path);
        }
        else if (strcmp(argv[i], "--hide-simple") == 0 && i + 1 < argc) {
            const char *path = argv[++i];
            simple_bind_hide(EMPTY_DIR, path);
        }
        else if (strcmp(argv[i], "--hide-android-data") == 0 && i + 1 < argc) {
            const char *path = argv[++i];
            if (android_data_namespace_mount(path) == 0) {
                snprintf(g_hidden_paths[g_hidden_count++], MAX_PATH_LEN, "%s", path);
            }
        }
        else if (strcmp(argv[i], "--list") == 0) {
            list_mounts();
        }
        else if (strcmp(argv[i], "--test") == 0) {
            return run_tests();
        }
        else if (strcmp(argv[i], "--idle") == 0) {
            return idle_mode();
        }
        else if (strcmp(argv[i], "--help") == 0 || strcmp(argv[i], "-h") == 0) {
            print_usage();
            return 0;
        }
        else {
            log_print("[main] 未知参数: %s\n", argv[i]);
            print_usage();
            return 1;
        }
    }

    /* 如果有 Android/data 隔离, 保持进程存活 */
    int has_android_data = 0;
    for (int i = 0; i < g_hidden_count; i++) {
        if (is_android_data_path(g_hidden_paths[i])) {
            has_android_data = 1;
            break;
        }
    }

    if (has_android_data) {
        log_print("[main] 存在 Android/data 隔离, 保持进程存活\n");
        log_print("[main] PID=%d, 等待信号退出\n", getpid());
        while (1) {
            pause();
        }
    }

    log_print("[main] 完成\n");
    return 0;
}
