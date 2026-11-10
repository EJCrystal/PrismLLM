package dev.soven.prismllm.proxy.fingerprint

import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/**
 * 数字指纹的特征提取，逐项对应 lm-detector 的 shared/fingerprint-core.js 与 shared/positional.ts。
 *
 * 参考库是用这套特征训练的，特征顺序或口径改动会让排名失真；调整时必须同步重训参考库。
 *
 * @author 文杰
 * @since 2026-11-10
 */
internal const val FINGERPRINT_VALUE_MIN = 1
internal const val FINGERPRINT_VALUE_MAX = 355
internal const val FINGERPRINT_DIMENSION = FINGERPRINT_VALUE_MAX - FINGERPRINT_VALUE_MIN + 1

private const val ALPHA = 0.5
private val DIGIT_RUN = Regex("\\d+")
private val SIGNED_INTEGER = Regex("-?\\d+")
private val POSITIONAL_WEIGHTS = doubleArrayOf(0.6, 0.15, 0.125, 0.125)
private val POSITIONAL_CENTRES = DoubleArray(24) { 1 + it * 354.0 / 23 }
private val SEQUENCE_LAGS = intArrayOf(1, 2, 3, 5, 8, 13, 21, 34)

/**
 * 取文本中最长的一段 1..355 整数。
 *
 * 与参考实现一致：数字本身允许任意长度；遇到字母会把当前序列切开，
 * 因此“第一组 1,2,3，第二组 4,5”只会保留较长的一段，而不是拼在一起。
 */
internal fun parseFingerprintNumbers(text: String): IntArray {
    val runs = mutableListOf<MutableList<Int>>()
    var current = mutableListOf<Int>()
    var previousEnd = 0
    for (match in DIGIT_RUN.findAll(text)) {
        val separator = text.substring(previousEnd, match.range.first)
        if (current.isNotEmpty() && containsLetter(separator)) {
            runs.add(current)
            current = mutableListOf()
        }
        val value = match.value.toIntOrNull()
        if (value != null && value in FINGERPRINT_VALUE_MIN..FINGERPRINT_VALUE_MAX) current.add(value)
        previousEnd = match.range.last + 1
    }
    if (current.isNotEmpty()) runs.add(current)
    val longest = runs.maxByOrNull { it.size } ?: emptyList()
    return longest.toIntArray()
}

/** 超出范围的原始整数数量；只用于诊断，不参与特征。 */
internal fun countOutOfRangeIntegers(text: String): Int =
    SIGNED_INTEGER.findAll(text).count { match ->
        val value = match.value.toLongOrNull()
        value == null || value < FINGERPRINT_VALUE_MIN || value > FINGERPRINT_VALUE_MAX
    }

private fun containsLetter(text: String): Boolean {
    var index = 0
    while (index < text.length) {
        val codePoint = text.codePointAt(index)
        if (Character.isLetter(codePoint)) return true
        index += Character.charCount(codePoint)
    }
    return false
}

internal fun countFingerprintNumbers(numbers: IntArray): IntArray {
    val counts = IntArray(FINGERPRINT_DIMENSION)
    for (number in numbers) counts[number - FINGERPRINT_VALUE_MIN] += 1
    return counts
}

internal fun hellingerFeature(counts: IntArray): DoubleArray {
    val total = counts.sum().toDouble() + ALPHA * FINGERPRINT_DIMENSION
    return DoubleArray(FINGERPRINT_DIMENSION) { sqrt((counts[it] + ALPHA) / total) }
}

internal fun orderedBlockFeature(numbers: IntArray): DoubleArray {
    val result = ArrayList<Double>(74)
    val base = numbers.size / 4
    val remainder = numbers.size % 4
    var start = 0
    for (block in 0 until 4) {
        val size = base + if (block < remainder) 1 else 0
        val bins = DoubleArray(16) { 0.5 }
        for (index in start until start + size) {
            val value = numbers[index]
            val bin = min(15, floor((value - 1) * 16.0 / 355).toInt())
            bins[bin] += 1
        }
        val total = bins.sum()
        for (bin in bins) result.add(sqrt(bin / total))
        start += size
    }
    val lastDigits = DoubleArray(10) { 0.5 }
    for (number in numbers) lastDigits[number % 10] += 1
    val total = lastDigits.sum()
    for (digit in lastDigits) result.add(sqrt(digit / total))
    return result.toDoubleArray()
}

private fun positionKernel(numbers: IntArray): DoubleArray {
    val size = numbers.size
    val output = DoubleArray(34 * 3)
    for (position in numbers.indices) {
        val value = numbers[position]
        val t = (position + 0.5) * 2 / size - 1
        val basis = doubleArrayOf(t, 0.5 * (3 * t * t - 1), 0.5 * (5 * t * t * t - 3 * t))
        var featureIndex = 0
        for (centre in POSITIONAL_CENTRES) {
            val weight = exp(-0.5 * ((value - centre) / 20).let { it * it })
            addKernel(output, featureIndex++, basis, weight / size)
        }
        val digit = value % 10
        for (candidate in 0 until 10) {
            addKernel(output, featureIndex++, basis, if (candidate == digit) 1.0 / size else 0.0)
        }
    }
    return output
}

private fun addKernel(output: DoubleArray, featureIndex: Int, basis: DoubleArray, weight: Double) {
    if (weight == 0.0) return
    for (j in basis.indices) output[featureIndex * 3 + j] += weight * basis[j]
}

private fun centredTransitions(symbols: IntArray, bins: Int): DoubleArray {
    val joint = DoubleArray(bins * bins) { 0.5 }
    val total = symbols.size - 1 + 0.5 * bins * bins
    for (index in 1 until symbols.size) joint[symbols[index - 1] * bins + symbols[index]] += 1
    for (index in joint.indices) joint[index] /= total
    val rows = DoubleArray(bins) { row -> (0 until bins).sumOf { joint[row * bins + it] } }
    val columns = DoubleArray(bins) { column -> (0 until bins).sumOf { joint[it * bins + column] } }
    return DoubleArray(joint.size) { index ->
        joint[index] - rows[index / bins] * columns[index % bins]
    }
}

private fun sequenceFeatures(numbers: IntArray): DoubleArray {
    val size = numbers.size
    val mean = meanOf(numbers.map { it.toDouble() }.toDoubleArray())
    var variance = 0.0
    for (number in numbers) variance += (number - mean) * (number - mean)
    val sd = max(sqrt(variance / size), 1.0)
    val standardized = DoubleArray(size) { (numbers[it] - mean) / sd }
    val output = ArrayList<Double>(220)
    for (lag in SEQUENCE_LAGS) {
        val count = size - lag
        if (count <= 0) {
            repeat(6) { output.add(Double.NaN) }
            continue
        }
        val differences = DoubleArray(count) { numbers[it + lag] - numbers[it].toDouble() }
        var lagProduct = 0.0
        for (index in 0 until count) lagProduct += standardized[index + lag] * standardized[index]
        output.add(lagProduct / count)
        output.add(differences.sumOf { abs(it) } / count / 355)
        output.add(differences.count { it > 0 }.toDouble() / count)
        output.add(differences.count { it == 0.0 }.toDouble() / count)
        output.add(differences.count { abs(it) <= 5 }.toDouble() / count)
        output.add(differences.count { abs(it) <= 20 }.toDouble() / count)
    }
    output.addAll(centredTransitions(IntArray(size) { min(7, floor((numbers[it] - 1) * 8.0 / 355).toInt()) }, 8).asList())
    output.addAll(centredTransitions(IntArray(size) { numbers[it] % 10 }, 10).asList())
    if (size >= 3) {
        val difference = IntArray(size - 1) { numbers[it + 1] - numbers[it] }
        val zStep = DoubleArray(size - 1) { abs(standardized[it + 1] - standardized[it]) }
        output.add(countMatching(size - 2) { index ->
            Integer.signum(difference[index + 1]) == Integer.signum(difference[index])
        })
        output.add(countMatching(size - 2) { index -> difference[index + 1] == difference[index] })
        output.add(countMatching(size - 1) { index -> numbers[index] % 10 == numbers[index + 1] % 10 })
        output.add(countMatching(size - 1) { index -> zStep[index] > 1 })
        output.add(countMatching(size - 1) { index -> zStep[index] > 2 })
    } else {
        repeat(5) { output.add(Double.NaN) }
    }
    val buckets = IntArray(size) { min(15, floor((numbers[it] - 1) * 16.0 / 355).toInt()) }
    for (width in intArrayOf(4, 8, 16)) {
        if (size < width) {
            output.add(Double.NaN)
            continue
        }
        var sum = 0.0
        for (start in 0..size - width) {
            sum += buckets.copyOfRange(start, start + width).toSet().size.toDouble() / width
        }
        output.add(sum / (size - width + 1))
    }
    return output.toDoubleArray()
}

private fun countMatching(length: Int, predicate: (Int) -> Boolean): Double {
    if (length <= 0) return Double.NaN
    var hits = 0
    for (index in 0 until length) if (predicate(index)) hits++
    return hits.toDouble() / length
}

internal fun positionalFeatures(numbers: IntArray): List<DoubleArray> = listOf(
    hellingerFeature(countFingerprintNumbers(numbers)),
    orderedBlockFeature(numbers),
    positionKernel(numbers),
    sequenceFeatures(numbers),
)

internal fun meanOf(values: DoubleArray): Double {
    if (values.isEmpty()) return Double.NaN
    return values.sum() / values.size
}

internal fun meanOf(values: List<Double>): Double {
    if (values.isEmpty()) return Double.NaN
    return values.sum() / values.size
}

internal fun dot(left: DoubleArray, right: DoubleArray): Double {
    var value = 0.0
    for (index in left.indices) value += left[index] * right[index]
    return value
}

internal fun norm(values: DoubleArray): Double = sqrt(dot(values, values))

internal fun normalized(values: DoubleArray): DoubleArray {
    val scale = max(norm(values), 1e-12)
    return DoubleArray(values.size) { values[it] / scale }
}

internal fun standardized(values: DoubleArray): DoubleArray {
    val mean = meanOf(values)
    var variance = 0.0
    for (value in values) variance += (value - mean) * (value - mean)
    val scale = max(sqrt(variance / values.size), 1e-12)
    return DoubleArray(values.size) { (values[it] - mean) / scale }
}

internal fun standardized(values: List<Double>): DoubleArray =
    standardized(values.toDoubleArray())

/** 减去一组正交基上的投影；系数按原向量一次性计算，与参考实现一致。 */
internal fun subtractBasis(values: DoubleArray, basis: List<DoubleArray>): DoubleArray {
    if (basis.isEmpty()) return values.copyOf()
    val coefficients = basis.map { dot(values, it) }
    return DoubleArray(values.size) { index ->
        var result = values[index]
        for (j in basis.indices) result -= coefficients[j] * basis[j][index]
        result
    }
}

internal fun median(values: DoubleArray): Double {
    if (values.isEmpty()) return Double.NaN
    val sorted = values.sortedArray()
    val middle = sorted.size / 2
    return if (sorted.size % 2 == 1) sorted[middle] else (sorted[middle - 1] + sorted[middle]) / 2
}

internal fun median(values: List<Double>): Double = median(values.toDoubleArray())

internal fun columnMean(rows: List<DoubleArray>): DoubleArray {
    if (rows.isEmpty()) return DoubleArray(0)
    val result = DoubleArray(rows[0].size)
    for (row in rows) for (index in result.indices) result[index] += row[index]
    for (index in result.indices) result[index] /= rows.size
    return result
}

internal fun transformBlocks(blocks: List<DoubleArray>, preprocessing: List<Preprocessing>): DoubleArray {
    val output = ArrayList<Double>()
    for (j in blocks.indices) {
        val block = blocks[j]
        val params = preprocessing[j]
        val scaled = DoubleArray(block.size) { index ->
            (block[index] - params.mean[index]) / params.scale[index]
        }
        val unit = normalized(scaled)
        val weight = sqrt(if (j == 0) 0.75 else 0.25)
        for (value in unit) output.add(value * weight)
    }
    return output.toDoubleArray()
}

internal fun centered(values: DoubleArray, bank: FeatureBank): DoubleArray =
    DoubleArray(values.size) { (values[it] - bank.featureMean[it]) / bank.featureScale[it] }

internal fun centered(values: DoubleArray, bank: OrderedFeatureBank): DoubleArray =
    DoubleArray(values.size) { (values[it] - bank.featureMean[it]) / bank.featureScale[it] }
internal fun positionalDecision(numbers: IntArray, head: PositionalHead): DoubleArray {
    val parts = positionalFeatures(numbers)
    val x = ArrayList<Double>()
    for (j in parts.indices) {
        val params = head.params[j]
        val block = parts[j]
        val scaled = DoubleArray(block.size) { (block[it] - params.mean[it]) / params.scale[it] }
        val length = max(norm(scaled), 1e-12)
        val weight = sqrt(POSITIONAL_WEIGHTS[j])
        for (value in scaled) x.add(value / length * weight)
    }
    return DoubleArray(head.weights.size) { index ->
        var sum = head.bias[index]
        val row = head.weights[index]
        for (i in x.indices) sum += row[i] * x[i]
        sum
    }
}

/**
 * 相邻数字的变化方式：参考库中的模型回答普遍有负的一阶自相关，或很少出现 ±20 以内的小步长。
 * 程序生成、排序或打乱顺序的数字通常两项都不满足。
 */
internal fun sequenceShape(numbers: List<IntArray>): FingerprintSequence {
    val shapes = numbers.map { sequence ->
        val mean = meanOf(sequence.map { it.toDouble() }.toDoubleArray())
        var variance = 0.0
        for (value in sequence) variance += (value - mean) * (value - mean)
        variance /= sequence.size
        val steps = sequence.size - 1
        var lagProduct = 0.0
        var smallSteps = 0
        for (index in 0 until steps) {
            lagProduct += (sequence[index + 1] - mean) * (sequence[index] - mean)
            if (abs(sequence[index + 1] - sequence[index]) <= 20) smallSteps++
        }
        val autocorrelation = if (variance != 0.0) lagProduct / steps / variance else 1.0
        val smallStepShare = if (steps > 0) smallSteps.toDouble() / steps else Double.NaN
        autocorrelation to smallStepShare
    }
    val autocorrelation = median(shapes.map { it.first })
    val smallStepShare = median(shapes.map { it.second })
    return FingerprintSequence(
        autocorrelation = autocorrelation,
        smallStepShare = smallStepShare,
        modelLike = autocorrelation <= -0.23 || smallStepShare <= 0.05,
    )
}
