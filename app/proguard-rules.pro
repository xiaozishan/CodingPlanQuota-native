# org.json 由 Android 运行时提供；保留其模型和成员，避免平台互操作路径被误裁剪。
-keep class org.json.** { *; }

# 保留协程的 ServiceLoader、异常处理和调度入口；库自身 consumer rules 仍继续生效。
-keep class kotlinx.coroutines.** { *; }

# Compose 使用 AGP 默认优化规则与各 AndroidX 构件随附的 consumer ProGuard rules，
# 不做全量 Compose keep，以免抵消 R8 的体积收益。
