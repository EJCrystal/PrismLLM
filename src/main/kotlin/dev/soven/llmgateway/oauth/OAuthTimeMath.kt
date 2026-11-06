package dev.soven.llmgateway.oauth

/**
 * 订阅账号调度中的时间运算。上游读数、恢复快照或异常时钟都可能给出极端值，
 * 统一使用饱和运算，避免加法/减法溢出成负数后绕过冷却或窗口限制。
 *
 * @author 文杰
 * @since 2026-11-06
 */
internal fun saturatedAdd(base: Long, delta: Long): Long =
    if (delta > 0 && base > Long.MAX_VALUE - delta) Long.MAX_VALUE else base + delta

internal fun saturatedSubtract(value: Long, delta: Long): Long =
    if (delta > 0 && value < Long.MIN_VALUE + delta) Long.MIN_VALUE else value - delta
