#
# Makefile - Android 16 ARM64 Mount Namespace 隔离工具
#
# 目标: logd (多文件链接)
#
# 文件结构:
#   main.c              - 入口, 路由两种逻辑
#   namespace.c          - namespace 创建与管理
#   mount_simple.c       - 普通目录 bind mount (逻辑一)
#   mount_android_data.c - Android/data namespace 隔离 (逻辑二)
#   logd.h              - 共享头文件
#
# 两种逻辑严格分离, 不互相调用
#

# NDK 路径 (可通过环境变量覆盖)
NDK_PATH ?= /data/user/work/android-sdk/ndk/26.3.11579264
TOOLCHAIN = $(NDK_PATH)/toolchains/llvm/prebuilt/linux-x86_64

# 编译器
CC = $(TOOLCHAIN)/bin/aarch64-linux-android21-clang

# 编译选项
CFLAGS = -O2 -s -Wall -Wextra -D_GNU_SOURCE

# 链接选项
LDFLAGS =

# 源文件
SRCS = main.c namespace.c mount_simple.c mount_android_data.c

# 头文件依赖
HDRS = logd.h

# 目标
TARGET = logd

# 输出目录
OUT_DIR ?= .

.PHONY: all clean test install

all: $(TARGET)

$(TARGET): $(SRCS) $(HDRS)
	$(CC) $(CFLAGS) -o $(OUT_DIR)/$(TARGET) $(SRCS) $(LDFLAGS)
	@echo "Built: $(OUT_DIR)/$(TARGET)"
	@file $(OUT_DIR)/$(TARGET)

# 单独编译各模块 (用于调试)
namespace.o: namespace.c logd.h
	$(CC) $(CFLAGS) -c -o $@ namespace.c

mount_simple.o: mount_simple.c logd.h
	$(CC) $(CFLAGS) -c -o $@ mount_simple.c

mount_android_data.o: mount_android_data.c logd.h
	$(CC) $(CFLAGS) -c -o $@ mount_android_data.c

main.o: main.c logd.h
	$(CC) $(CFLAGS) -c -o $@ main.c

# 运行自测
test: $(TARGET)
	adb push $(OUT_DIR)/$(TARGET) /data/local/tmp/
	adb shell "chmod 755 /data/local/tmp/$(TARGET)"
	adb shell "su -c '/data/local/tmp/$(TARGET) --test'"

# 安装到设备
install: $(TARGET)
	adb push $(OUT_DIR)/$(TARGET) /data/local/tmp/
	adb shell "chmod 755 /data/local/tmp/$(TARGET)"

# 在设备上测试普通目录
test-simple: install
	adb shell "su -c '/data/local/tmp/$(TARGET) --hide-simple /data/local/tmp/test_dir'"
	adb shell "ls /data/local/tmp/test_dir"
	adb shell "su -c '/data/local/tmp/$(TARGET) --unhide /data/local/tmp/test_dir'"

# 在设备上测试 Android/data
test-android-data: install
	adb shell "su -c '/data/local/tmp/$(TARGET) --hide-android-data /storage/emulated/0/Android/data/com.omarea.vtools'"

clean:
	rm -f $(TARGET) *.o
