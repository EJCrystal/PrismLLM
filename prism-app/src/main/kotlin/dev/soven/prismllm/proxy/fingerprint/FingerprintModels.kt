package dev.soven.prismllm.proxy.fingerprint

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/** 图像之外的四块位置特征预处理参数。顺序与参考库训练时的 block 顺序一致。 */
internal data class Preprocessing(
    val mean: DoubleArray,
    val scale: DoubleArray,
)

internal data class FeatureBank(
    val featureMean: DoubleArray,
    val featureScale: DoubleArray,
    val nuisanceBasis: List<DoubleArray>,
    val centroids: List<DoubleArray>,
)

internal data class OrderedFeatureBank(
    val featureMean: DoubleArray,
    val featureScale: DoubleArray,
    val nuisanceBasis: List<DoubleArray>,
    val centroids: List<DoubleArray>,
    val environmentCentroids: List<List<DoubleArray>>,
)

internal data class PositionalHead(
    val params: List<Preprocessing>,
    val weights: List<DoubleArray>,
    val bias: DoubleArray,
)

@Serializable
data class FingerprintModelInfo(
    val id: String,
    @SerialName("display_name") val displayName: String,
    val family: String,
    @SerialName("family_name") val familyName: String,
    @SerialName("response_count") val responseCount: Int,
)

@Serializable
data class FingerprintCandidate(
    val model: String,
    val displayName: String,
    val family: String,
    val familyName: String,
    val score: Double,
    val probability: Double? = null,
)

@Serializable
data class FingerprintDiagnostic(
    val index: Int,
    val parsedNumbers: Int,
    val minimumNumbers: Int,
    val accepted: Boolean,
    val rawOutOfRangeCount: Int,
)

@Serializable
data class FingerprintSequence(
    val autocorrelation: Double,
    val smallStepShare: Double,
    val modelLike: Boolean,
)

/** 一次指纹检测的本地评分结果；参考库只做闭集排序，不构成模型身份证明。 */
@Serializable
data class FingerprintAnalysis(
    val decision: String,
    val method: String,
    val prediction: String? = null,
    val predictionName: String,
    val familyPrediction: String? = null,
    val familyPredictionName: String,
    val probability: Double? = null,
    val probabilityStatus: String,
    val usedOutputs: Int,
    val totalOutputs: Int,
    val rankingScore: Double? = null,
    val results: List<FingerprintCandidate> = emptyList(),
    val diagnostics: List<FingerprintDiagnostic> = emptyList(),
    val sequence: FingerprintSequence? = null,
    val evidenceLabel: String,
    val evidenceReason: String,
    val detectorSha256: String,
    val bankBuiltAt: String,
)

@Serializable
data class FingerprintOutput(
    val expectedCount: Int,
    val text: String,
)

@Serializable
data class FingerprintChallenge(
    val index: Int,
    val expectedCount: Int,
    val prompt: String,
)

@Serializable
data class FingerprintSamplePayload(
    val index: Int,
    val expectedCount: Int,
    val prompt: String,
    val text: String? = null,
    val parsedNumbers: Int,
    val minimumNumbers: Int,
    val accepted: Boolean,
    val statusCode: Int? = null,
    val latencyMs: Long,
    val errorMessage: String? = null,
    val reportedModel: String? = null,
)