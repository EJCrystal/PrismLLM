package dev.soven.prismllm.shared.domain

/**
 * 后端用户可见文案的时长单位规则，与前端 `formatDuration` 保持一致：
 * 小于 1 秒用 `ms`，大于等于 1 秒一律换算成 `s`（整秒不带小数，非整秒保留 1 位）。
 *
 * 配置项的范围校验属于「输入口径」，仍按毫秒描述，不要用本函数。
 *
 * @author 文杰
 * @since 2026-11-10
 */
public fun formatDurationText(millis: Long): String {
    if (millis < 1_000) return "${millis}ms"
    // 先按 1 位小数取整再判断整秒，否则 59999ms 会输出 "60.0s"。
    val tenths = Math.round(millis / 100.0)
    val text = if (tenths % 10 == 0L) (tenths / 10).toString() else "${tenths / 10}.${tenths % 10}"
    return "${text}s"
}
