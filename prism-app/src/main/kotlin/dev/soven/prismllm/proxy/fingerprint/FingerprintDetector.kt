package dev.soven.prismllm.proxy.fingerprint

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import kotlin.math.ceil
import kotlin.math.exp
import kotlin.math.max
import kotlin.math.min

/**
 * 本地数字指纹检测器：读取 lm-detector 的共享参考库并对模型回答排名。
 *
 * 参考库是闭集：不在库中的模型也会得到“最像”的候选，概率只是库内相对值。
 * 数据来源与许可见 `resources/fingerprint/models.json` 与 `resources/fingerprint/LICENSE`。
 */
class FingerprintDetector private constructor(
    val modelIds: List<String>,
    private val modelInfo: Map<String, FingerprintModelInfo>,
    val bankBuiltAt: String,
    val detectorSha256: String,
    private val headParams: List<Preprocessing>,
    private val fullParams: List<Preprocessing>,
    private val ldaWeights: List<DoubleArray>,
    private val ldaBias: DoubleArray,
    private val references: List<List<DoubleArray>>,
    private val referenceSquaredNorms: List<DoubleArray>,
    private val hellingerBank: FeatureBank,
    private val orderedBank: OrderedFeatureBank,
    private val blendFrequency: Double,
    private val blendPositional: Double,
    private val positionalHead: PositionalHead,
    private val calibrationTau: Double?,
) {
    val modelCount: Int get() = modelIds.size

    /** 允许部分有效回答；不足三条时只给排名、不给置信度。 */
    fun analyze(outputs: List<FingerprintOutput>, allowPartial: Boolean = true): FingerprintAnalysis {
        val parsed = outputs.map { parseFingerprintNumbers(it.text) }
        val diagnostics = outputs.mapIndexed { index, output ->
            val minimum = max(80, ceil(output.expectedCount * 0.55).toInt())
            FingerprintDiagnostic(
                index = index,
                parsedNumbers = parsed[index].size,
                minimumNumbers = minimum,
                accepted = parsed[index].size >= minimum,
                rawOutOfRangeCount = countOutOfRangeIntegers(output.text),
            )
        }
        val accepted = parsed.filterIndexed { index, _ -> diagnostics[index].accepted }
        val used = accepted.size
        val sequence = if (used > 0) sequenceShape(accepted) else null
        val notModel = FingerprintAnalysis(
            decision = "not_model",
            method = "sequence-shape",
            prediction = null,
            predictionName = "不像语言模型回答",
            familyPrediction = null,
            familyPredictionName = "",
            probabilityStatus = "unavailable",
            usedOutputs = used,
            totalOutputs = outputs.size,
            results = emptyList(),
            diagnostics = diagnostics,
            sequence = sequence,
            evidenceLabel = "不像语言模型回答",
            evidenceReason = "相邻数字的变化方式与参考库中的语言模型回答不同，常见于程序生成、排序或打乱顺序的数字。未生成排名。",
            detectorSha256 = detectorSha256,
            bankBuiltAt = bankBuiltAt,
        )
        if (allowPartial && used in 1..2 && outputs.size <= 3) {
            if (sequence != null && !sequence.modelLike) return notModel
            val ranking = rankNumbers(accepted)
            return analysisWithRanking(
                ranking = ranking,
                probabilities = null,
                used = used,
                totalOutputs = outputs.size,
                diagnostics = diagnostics,
                sequence = sequence,
                decision = "partial",
                method = "shared-ranker-partial-v1",
                evidenceLabel = "部分样本排名",
                evidenceReason = "使用 $used/3 条有效回答生成排名。补齐三条有效回答后才能计算置信度。",
            )
        }
        if (outputs.size != 3 || used != 3) {
            return FingerprintAnalysis(
                decision = "unscorable",
                method = "complete-three-answers",
                predictionName = "需要三条完整回答",
                familyPredictionName = "",
                probabilityStatus = "unavailable",
                usedOutputs = used,
                totalOutputs = outputs.size,
                diagnostics = diagnostics,
                sequence = sequence,
                evidenceLabel = "需要三条完整回答",
                evidenceReason = "当前有 $used/${outputs.size} 条有效回答。请补齐原来的三条回答后重新检测。",
                detectorSha256 = detectorSha256,
                bankBuiltAt = bankBuiltAt,
            )
        }
        if (sequence == null || !sequence.modelLike) return notModel
        val ranking = rankNumbers(accepted)
        val probabilities = calibrateRanking(ranking)
        return analysisWithRanking(
            ranking = ranking,
            probabilities = probabilities,
            used = used,
            totalOutputs = outputs.size,
            diagnostics = diagnostics,
            sequence = sequence,
            decision = "scored",
            method = if (probabilities != null) "reference-calibrated-ranking" else "uncalibrated-ranking",
            evidenceLabel = if (probabilities != null) "库内校准排名" else "未校准排名",
            evidenceReason = if (probabilities != null) {
                "置信度是参考库内的闭集相对概率，不包含库外模型。"
            } else {
                "校准参数与当前检测器不匹配，置信度不可用。候选顺序由排名分数决定。"
            },
        )
    }

    private fun analysisWithRanking(
        ranking: DoubleArray,
        probabilities: DoubleArray?,
        used: Int,
        totalOutputs: Int,
        diagnostics: List<FingerprintDiagnostic>,
        sequence: FingerprintSequence?,
        decision: String,
        method: String,
        evidenceLabel: String,
        evidenceReason: String,
    ): FingerprintAnalysis {
        val order = modelIds.indices.sortedWith(compareByDescending { ranking[it] })
        val results = order.map { index ->
            val info = infoFor(modelIds[index])
            FingerprintCandidate(
                model = modelIds[index],
                displayName = info.displayName,
                family = info.family,
                familyName = info.familyName,
                score = ranking[index],
                probability = probabilities?.getOrNull(index),
            )
        }
        val first = results.first()
        return FingerprintAnalysis(
            decision = decision,
            method = method,
            prediction = first.model,
            predictionName = first.displayName,
            familyPrediction = first.family,
            familyPredictionName = first.familyName,
            probability = first.probability,
            probabilityStatus = if (probabilities != null) "reference_calibrated" else "unavailable",
            usedOutputs = used,
            totalOutputs = totalOutputs,
            rankingScore = ranking[order.first()],
            results = results,
            diagnostics = diagnostics,
            sequence = sequence,
            evidenceLabel = evidenceLabel,
            evidenceReason = evidenceReason,
            detectorSha256 = detectorSha256,
            bankBuiltAt = bankBuiltAt,
        )
    }

    private fun infoFor(id: String): FingerprintModelInfo =
        modelInfo[id] ?: FingerprintModelInfo(id, id, "other", "其他", 0)

    private fun calibrateRanking(ranking: DoubleArray): DoubleArray? {
        val tau = calibrationTau ?: return null
        if (!tau.isFinite() || tau < 0.001 || tau > 1000) return null
        if (ranking.size != modelIds.size || ranking.any { !it.isFinite() }) return null
        val logits = DoubleArray(ranking.size) { tau * ranking[it] }
        val maximum = logits.max()
        val weights = DoubleArray(logits.size) { exp(logits[it] - maximum) }
        val sum = weights.sum()
        return DoubleArray(weights.size) { weights[it] / sum }
    }

    /** 对应 lm-detector 的 rankSharedNumbers。 */
    internal fun rankNumbers(numbers: List<IntArray>): DoubleArray {
        val full = numbers.map { number ->
            listOf(hellingerFeature(countFingerprintNumbers(number)), orderedBlockFeature(number))
        }
        val ax = full.map { transformBlocks(it, fullParams) }
        val lda = numbers.map { number ->
            val head = number.copyOf(min(128, number.size))
            val blocks = listOf(hellingerFeature(countFingerprintNumbers(head)), orderedBlockFeature(head))
            val x = transformBlocks(blocks, headParams)
            standardized(ldaWeights.mapIndexed { index, row -> dot(row, x) + ldaBias[index] })
        }
        val frequencyHead = standardized(columnMean(lda))
        val near = standardized(references.mapIndexed { index, reference ->
            median(ax.map { -nearestMeanDistance(it, reference, referenceSquaredNorms[index]) })
        })
        val base = standardized(columnMean(full.map { baseline(it) }))
        val frequency = standardized(DoubleArray(frequencyHead.size) { index ->
            0.5 * frequencyHead[index] + 0.25 * near[index] + 0.25 * base[index]
        })
        val positional = standardized(columnMean(numbers.map { number ->
            standardized(positionalDecision(number, positionalHead))
        }))
        val ranking = DoubleArray(frequency.size) { index ->
            blendFrequency * frequency[index] + blendPositional * positional[index]
        }
        if (ranking.any { !it.isFinite() }) throw IllegalStateException("排名计算产生无效数值")
        return ranking
    }

    internal fun nearestMeanDistance(
        x: DoubleArray,
        reference: List<DoubleArray>,
        squaredNorms: DoubleArray,
    ): Double {
        val xx = dot(x, x)
        val distances = DoubleArray(reference.size) { index ->
            max(0.0, xx + squaredNorms[index] - 2 * dot(x, reference[index]))
        }
        distances.sort()
        val take = min(7, distances.size)
        var sum = 0.0
        for (index in 0 until take) sum += distances[index]
        return sum / take
    }

    private fun baseline(blocks: List<DoubleArray>): DoubleArray {
        val hellinger = blocks[0]
        val ordered = blocks[1]
        val hUnit = normalized(subtractBasis(centered(hellinger, hellingerBank), hellingerBank.nuisanceBasis))
        val marginal = DoubleArray(hellingerBank.centroids.size) { dot(hUnit, hellingerBank.centroids[it]) }

        val orderedCentered = centered(ordered, orderedBank)
        val raw = normalized(orderedCentered)
        val projected = normalized(subtractBasis(orderedCentered, orderedBank.nuisanceBasis))
        val templates = DoubleArray(orderedBank.centroids.size) { modelIndex ->
            orderedBank.environmentCentroids.maxOf { environment -> dot(raw, environment[modelIndex]) }
        }
        val nuisance = DoubleArray(orderedBank.centroids.size) { dot(projected, orderedBank.centroids[it]) }
        val templateZ = standardized(templates)
        val nuisanceZ = standardized(nuisance)
        val orderedScores = standardized(DoubleArray(templateZ.size) { 0.5 * templateZ[it] + 0.5 * nuisanceZ[it] })
        val marginalZ = standardized(marginal)
        return DoubleArray(marginalZ.size) { 0.5 * marginalZ[it] + 0.5 * orderedScores[it] }
    }

    companion object {
        private const val DETECTOR_RESOURCE = "/fingerprint/shared_detector.bin"
        private const val METADATA_RESOURCE = "/fingerprint/models.json"

        fun loadDefault(): FingerprintDetector {
            val bytes = FingerprintDetector::class.java.getResourceAsStream(DETECTOR_RESOURCE)
                ?.use { it.readBytes() }
                ?: error("缺少指纹检测参考库 $DETECTOR_RESOURCE")
            val metadataText = FingerprintDetector::class.java.getResourceAsStream(METADATA_RESOURCE)
                ?.use { it.readBytes().toString(StandardCharsets.UTF_8) }
                ?: error("缺少指纹检测模型元数据 $METADATA_RESOURCE")
            val metadata = Json.decodeFromString<FingerprintBankMetadata>(metadataText)
            return decode(bytes, metadata)
        }

        private fun decode(bytes: ByteArray, metadata: FingerprintBankMetadata): FingerprintDetector {
            require(bytes.size >= 8) { "指纹检测参考库为空或已损坏" }
            val headerLength = (ByteBuffer.wrap(bytes, 0, 4).order(ByteOrder.LITTLE_ENDIAN).int.toLong() and 0xFFFFFFFFL).toInt()
            require(headerLength > 0 && 4L + headerLength <= bytes.size) { "指纹检测参考库头部无效" }
            val start = ((4L + headerLength + 3) / 4 * 4).toInt()
            require((bytes.size - start) % 4 == 0) { "指纹检测参考库浮点区长度无效" }
            val count = (bytes.size - start) / 4
            val floats = FloatArray(count) { index ->
                var bits = 0
                for (byte in 0 until 4) {
                    bits = bits or ((bytes[start + byte * count + index].toInt() and 0xFF) shl (byte * 8))
                }
                Float.fromBits(bits)
            }
            val header = runCatching {
                Json.parseToJsonElement(String(bytes, 4, headerLength, StandardCharsets.UTF_8)).jsonObject
            }.getOrElse { error("指纹检测参考库头部不是有效 JSON: ${it.message}") }
            fun elementFloats(element: JsonElement): DoubleArray = when (element) {
                is JsonObject -> {
                    val offset = element["\$f32"]?.jsonPrimitive?.intOrNull
                        ?: error("浮点引用缺少偏移")
                    val size = element["n"]?.jsonPrimitive?.intOrNull ?: error("浮点引用缺少长度")
                    DoubleArray(size) { floats[offset + it].toDouble() }
                }
                is JsonArray -> DoubleArray(element.size) { element[it].jsonPrimitive.doubleOrNull ?: error("浮点值无效") }
                else -> error("不是浮点数组")
            }
            fun elementMatrix(element: JsonElement): List<DoubleArray> =
                (element as? JsonArray)?.map(::elementFloats) ?: error("不是二维数组")
            fun elementTensor(element: JsonElement): List<List<DoubleArray>> =
                (element as? JsonArray)?.map { row ->
                    (row as? JsonArray)?.map(::elementFloats) ?: error("不是三维数组")
                } ?: error("不是三维数组")
            val sha256 = sha256(bytes)
            require(metadata.source.detectorSha256.equals(sha256, ignoreCase = true)) {
                "指纹检测参考库与元数据不匹配（sha256 ${sha256.take(12)} != ${metadata.source.detectorSha256.take(12)}）"
            }
            val ranker = header.obj("ranker")
            val bank = ranker.obj("bank")
            val modelIds = header.array("model_ids").map {
                (it as? JsonPrimitive)?.contentOrNull ?: error("模型 id 无效")
            }
            fun featureBank(): FeatureBank {
                val source = bank.obj("hellinger")
                return FeatureBank(
                    featureMean = elementFloats(source.element("feature_mean")),
                    featureScale = elementFloats(source.element("feature_scale")),
                    nuisanceBasis = elementMatrix(source.element("nuisance_basis")),
                    centroids = elementMatrix(source.element("centroids")),
                )
            }
            val orderedSource = bank.obj("ordered_blocks")
            val orderedBank = OrderedFeatureBank(
                featureMean = elementFloats(orderedSource.element("feature_mean")),
                featureScale = elementFloats(orderedSource.element("feature_scale")),
                nuisanceBasis = elementMatrix(orderedSource.element("nuisance_basis")),
                centroids = elementMatrix(orderedSource.element("centroids")),
                environmentCentroids = elementTensor(orderedSource.element("environment_centroids")),
            )
            val hellingerBank = featureBank()
            val headParams = parsePreprocessing(ranker.element("head_params"), ::elementFloats)
            val fullParams = parsePreprocessing(ranker.element("full_params"), ::elementFloats)
            val positionalSource = ranker.obj("positional")
            val positionalHead = PositionalHead(
                params = parsePreprocessing(positionalSource.element("params"), ::elementFloats),
                weights = elementMatrix(positionalSource.element("weights")),
                bias = elementFloats(positionalSource.element("bias")),
            )
            val references = elementTensor(ranker.array("references"))
            require(references.size == modelIds.size) { "参考向量与模型数量不一致" }
            require(references.all { it.isNotEmpty() }) { "参考向量为空" }
            val expectedDimension = FINGERPRINT_DIMENSION + 74
            require(references.all { model -> model.all { it.size == expectedDimension } }) {
                "参考向量维度不是 $expectedDimension"
            }
            val info = metadata.models.associateBy { it.id }
            val bankBuiltAt = header.text("bank_built_at")
            val calibrationTau = runCatching {
                val calibration = header["calibration"]?.jsonObject ?: return@runCatching null
                val binding = calibration["binding"]?.jsonObject ?: return@runCatching null
                val bindingIds = (binding["model_ids"] as? JsonArray)?.map { (it as JsonPrimitive).contentOrNull }
                val valid = calibration.text("schema") == "shared-confidence-v2" &&
                    calibration.text("method") == "ranking-temperature" &&
                    bindingIds == modelIds
                if (!valid) return@runCatching null
                calibration["tau"]?.jsonPrimitive?.doubleOrNull?.takeIf { it.isFinite() && it in 0.001..1000.0 }
            }.getOrNull()
            return FingerprintDetector(
                modelIds = modelIds,
                modelInfo = info,
                bankBuiltAt = bankBuiltAt,
                detectorSha256 = sha256,
                headParams = headParams,
                fullParams = fullParams,
                ldaWeights = elementMatrix(ranker.element("lda_weights")),
                ldaBias = elementFloats(ranker.element("lda_bias")),
                references = references,
                referenceSquaredNorms = references.map { model ->
                    DoubleArray(model.size) { dot(model[it], model[it]) }
                },
                hellingerBank = hellingerBank,
                orderedBank = orderedBank,
                blendFrequency = ranker.obj("blend").number("frequency"),
                blendPositional = ranker.obj("blend").number("positional"),
                positionalHead = positionalHead,
                calibrationTau = calibrationTau,
            )
        }

        private fun parsePreprocessing(
            source: JsonElement,
            floats: (JsonElement) -> DoubleArray,
        ): List<Preprocessing> {
            val array = source as? JsonArray ?: error("预处理参数不是数组")
            return array.map { element ->
                val params = element.jsonObject
                Preprocessing(floats(params.element("mean")), floats(params.element("scale")))
            }
        }

        private fun JsonObject.array(key: String): JsonArray =
            this[key] as? JsonArray ?: error("缺少数组字段 $key")

        private fun JsonObject.element(key: String): JsonElement =
            this[key] ?: error("缺少字段 $key")

        private fun JsonObject.obj(key: String): JsonObject =
            this[key] as? JsonObject ?: error("缺少对象字段 $key")

        private fun JsonObject.text(key: String): String =
            (this[key] as? JsonPrimitive)?.contentOrNull ?: error("缺少文本字段 $key")

        private fun JsonObject.number(key: String): Double =
            (this[key] as? JsonPrimitive)?.doubleOrNull ?: error("缺少数值字段 $key")

        private fun sha256(bytes: ByteArray): String =
            MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
    }
}

@Serializable
internal data class FingerprintBankMetadata(
    val schema: String,
    val source: FingerprintBankSource,
    val models: List<FingerprintModelInfo>,
)

@Serializable
internal data class FingerprintBankSource(
    val project: String,
    val license: String,
    @SerialName("detector_file") val detectorFile: String,
    @SerialName("detector_sha256") val detectorSha256: String,
    @SerialName("bank_built_at") val bankBuiltAt: String,
    @SerialName("reference_schema") val referenceSchema: String,
)