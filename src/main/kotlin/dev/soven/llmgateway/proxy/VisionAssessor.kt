package dev.soven.llmgateway.proxy

import dev.soven.llmgateway.spi.iq.IqJudgeVerdict
import dev.soven.llmgateway.spi.iq.IqStructureSummary
import io.ktor.client.HttpClient
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.content.TextContent
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import java.util.Base64

data class VisionFrame(
    val timeSeconds: Float,
    val png: ByteArray,
)

data class VisionAnimationInfo(
    val durationSeconds: Float,
    val frameCount: Int,
    val framesIdentical: Boolean,
    val hasSmil: Boolean,
    val hasCss: Boolean,
)

data class VisionRequest(
    val frames: List<VisionFrame>,
    val benchmark: String,
    val structure: IqStructureSummary,
    val animation: VisionAnimationInfo,
)

enum class VisionAspectStatus(val label: String) {
    PASSED("通过"),
    FAILED("未通过"),
    UNKNOWN("无法确认"),
}

data class VisionAspect(
    val key: String,
    val status: VisionAspectStatus,
    val detail: String,
)

data class VisionAssessment(
    val verdict: IqJudgeVerdict,
    val score: Double? = null,
    val reason: String? = null,
    val aspects: List<VisionAspect> = emptyList(),
)

/** 视觉判分器的最小接口，便于测试时替换模型调用。 */
interface VisionAssessor {
    suspend fun assess(request: VisionRequest): VisionAssessment
}

/**
 * 通过 OpenAI 兼容的 `/chat/completions` 接口调用多模态模型。
 *
 * 端点由 `IQ_VISION_BASE_URL` 指定，可以指向本机 PrismLLM 网关，也可以是自建视觉服务；
 * API Key 只保存在网关进程内，不写入数据库，也不会回显到管理台。
 *
 * 每次请求会发送多张按时间采样的截图，要求模型按五个维度逐项检测并返回结构化结果。
 */
class OpenAiVisionAssessor(
    private val httpClient: HttpClient,
    baseUrl: String,
    private val apiKey: String? = null,
    private val model: String,
    private val timeoutMs: Long = 60_000,
) : VisionAssessor {
    private val baseUrl = baseUrl.trimEnd('/')

    override suspend fun assess(request: VisionRequest): VisionAssessment {
        if (request.frames.isEmpty()) {
            return failure("没有可发送的截图")
        }
        return try {
            withTimeout(timeoutMs) {
                val response = httpClient.post("$baseUrl/chat/completions") {
                    header(HttpHeaders.ContentType, ContentType.Application.Json.toString())
                    apiKey?.takeIf { it.isNotBlank() }?.let { header(HttpHeaders.Authorization, "Bearer $it") }
                    setBody(
                        TextContent(
                            buildJsonObject {
                                put("model", model)
                                put("temperature", 0)
                                put("max_tokens", 3_000)
                                putJsonArray("messages") {
                                    addJsonObject {
                                        put("role", "system")
                                        put("content", "你是图形作品评审，只输出一个 JSON 对象，不要 Markdown。")
                                    }
                                    addJsonObject {
                                        put("role", "user")
                                        putJsonArray("content") {
                                            addJsonObject {
                                                put("type", "text")
                                                put("text", prompt(request))
                                            }
                                            request.frames.forEachIndexed { index, frame ->
                                                addJsonObject {
                                                    put("type", "text")
                                                    put("text", "第 ${index + 1} 帧（t=${frame.timeSeconds}s）")
                                                }
                                                addJsonObject {
                                                    put("type", "image_url")
                                                    putJsonObject("image_url") {
                                                        put(
                                                            "url",
                                                            "data:image/png;base64," +
                                                                Base64.getEncoder().encodeToString(frame.png),
                                                        )
                                                    }
                                                }
                                            }
                                        }
                                    }
                                }
                            }.toString(),
                            ContentType.Application.Json,
                        ),
                    )
                }
                val body = response.bodyAsText()
                if (response.status.value !in 200..299) {
                    return@withTimeout failure("视觉模型返回 HTTP ${response.status.value}：${body.take(300)}")
                }
                val text = extractContent(body)
                    ?: return@withTimeout failure("视觉模型返回了无法解析的响应")
                parseAssessment(text)
            }
        } catch (cause: TimeoutCancellationException) {
            failure("视觉模型调用超时（${timeoutMs / 1000} 秒）")
        } catch (cause: CancellationException) {
            throw cause
        } catch (cause: Throwable) {
            failure("无法连接视觉模型：${cause.message?.take(200) ?: cause::class.simpleName}")
        }
    }

    private fun prompt(request: VisionRequest): String = buildString {
        val animation = request.animation
        appendLine("题目：用 SVG 创作一段鹈鹕骑自行车的动画，必须有真实、连续、肉眼可见的动态效果。")
        appendLine("你会收到 ${request.frames.size} 张按时间顺序采样的静态帧，请逐帧比较。")
        appendLine(
            "本地结构检查：HTML=${request.structure.htmlPresent}，SVG=${request.structure.svgPresent}，" +
                "动画声明=${request.structure.animationPresent}，无外部资源=${request.structure.selfContained}。",
        )
        appendLine(
            "动画采样：周期约 ${animation.durationSeconds} 秒，共 ${animation.frameCount} 帧；" +
                "检测到 SMIL=${animation.hasSmil}，CSS 动画=${animation.hasCss}，各帧截图完全相同=${animation.framesIdentical}。",
        )
        appendLine(
            "截图由无头浏览器在指定时间点渲染：CSS @keyframes 和 SMIL 动画都已推进到对应时刻，" +
                "各帧差异就是画面的真实动态变化。",
        )
        if (animation.framesIdentical) {
            appendLine(
                "注意：各帧截图完全相同，说明画面没有产生可见的动态效果；动画连贯性必须标记 failed，" +
                    "不要标记 unknown。",
            )
        }
        appendLine("请逐项检测：")
        appendLine(
            "1. 鹈鹕特征：是否同一只白色长颈鸟；头颈与躯干是否连贯；头部前方是否有很长的宽喙，" +
                "喙下方是否有显眼的橙色喉囊；冠羽和眼睛是否清楚可见。",
        )
        appendLine(
            "2. 自行车结构：是否有前后两个带辐条的轮子；后轮、座管、曲柄与前轮之间是否有连续的双三角车架；" +
                "车座、前叉、车把和链条是否可辨；轮轴与曲柄位置是否协调。",
        )
        appendLine(
            "3. 骑行关系：鹈鹕下腹部是否与车座相接并覆盖座面；两条腿是否从躯干下缘连续伸出到脚；" +
                "脚是否落在对应踏板；伸向前方的翅膀末端是否接触车把握把。",
        )
        appendLine(
            "4. 动画连贯性：逐帧比较轮辐/反光片方位、腿脚与曲柄/踏板的位置，说明是否显示连续运动，" +
                "是否存在同一只脚明显脱离踏板。",
        )
        appendLine(
            "5. 画面完成度：主体是否完整置于画面中央；鹈鹕、车架和两轮轮廓是否清楚；" +
                "是否有裁切、漂浮、错误重叠、缺失、错位或明显结构碰撞。",
        )
        appendLine(
            "判定规则：五项全部通过才是 passed。出现以下任一情况判为 incorrect（疑似降智）：" +
                "部件漂浮、错误重叠、缺失、错位、画面截断；鹈鹕没有坐在车座上；脚没有在踏板上；" +
                "翅膀没有握住车把；自行车结构不合理；缺少两个轮子、车架、车把、踏板或链条。" +
                "动画连贯性只能标记 passed 或 failed，不得标记 unknown；其他项目无法判断时可标记 unknown；" +
                "没有明确失败时 verdict 为 review。",
        )
        appendLine("只返回一个 JSON 对象，不要 Markdown。格式：")
        append(
            """{"aspects":{"鹈鹕特征":{"status":"passed|failed|unknown","detail":"中文描述"},"自行车结构":{"status":"passed|failed|unknown","detail":"中文描述"},"骑行关系":{"status":"passed|failed|unknown","detail":"中文描述"},"动画连贯性":{"status":"passed|failed|unknown","detail":"中文描述"},"画面完成度":{"status":"passed|failed|unknown","detail":"中文描述"}},"verdict":"passed|incorrect|review","score":0-100,"reason":"不超过 120 字"}""",
        )
    }

    private fun extractContent(body: String): String? {
        val json = runCatching { Json.parseToJsonElement(body).jsonObject }.getOrNull() ?: return null
        val content = json["choices"]?.jsonArray?.firstOrNull()?.jsonObject
            ?.get("message")?.jsonObject?.get("content")
            ?: return null
        return when (content) {
            is JsonPrimitive -> content.contentOrNull
            is JsonArray -> content.joinToString("") { part ->
                (part as? JsonObject)?.get("text")?.jsonPrimitive?.contentOrNull.orEmpty()
            }.takeIf { it.isNotBlank() }
            else -> null
        }
    }

    internal fun parseAssessment(text: String): VisionAssessment {
        val start = text.indexOf('{')
        val end = text.lastIndexOf('}')
        if (start < 0 || end <= start) return failure("视觉模型未返回 JSON 评分")
        val json = runCatching {
            Json.parseToJsonElement(text.substring(start, end + 1)).jsonObject
        }.getOrNull() ?: return failure("视觉模型返回的 JSON 无法解析")
        val aspects = parseAspects(json)
        val score = (json["score"] as? JsonPrimitive)?.contentOrNull?.toDoubleOrNull()?.coerceIn(0.0, 100.0)
        val explicit = parseVerdict((json["verdict"] as? JsonPrimitive)?.contentOrNull)
        val reason = (json["reason"] as? JsonPrimitive)?.contentOrNull?.take(500)
        val hasFailed = aspects.any { it.status == VisionAspectStatus.FAILED }
        val hasUnknown = aspects.any { it.status == VisionAspectStatus.UNKNOWN }
        val verdict = when {
            hasFailed -> IqJudgeVerdict.INCORRECT
            explicit == IqJudgeVerdict.INCORRECT -> IqJudgeVerdict.INCORRECT
            // 五项必检维度缺失时，不能只凭模型自报 passed 或分数自动通过。
            aspects.isEmpty() -> IqJudgeVerdict.REVIEW
            hasUnknown -> IqJudgeVerdict.REVIEW
            explicit != null -> explicit
            else -> IqJudgeVerdict.PASSED
        }
        return VisionAssessment(verdict, score, reason, aspects)
    }

    private fun parseAspects(json: JsonObject): List<VisionAspect> {
        val aspects = json["aspects"] as? JsonObject ?: return emptyList()
        return ASPECT_KEYS.map { key ->
            val entry = aspects.entries.firstOrNull { (name, _) -> name == key || name.contains(key) }
                ?.value as? JsonObject
            if (entry == null) {
                VisionAspect(key, VisionAspectStatus.UNKNOWN, "模型未返回该项检测结果。")
            } else {
                val detail = listOf("detail", "reason", "text")
                    .firstNotNullOfOrNull { field ->
                        (entry[field] as? JsonPrimitive)?.contentOrNull?.takeIf { it.isNotBlank() }
                    }
                    ?: "模型未提供描述。"
                VisionAspect(key, parseAspectStatus((entry["status"] as? JsonPrimitive)?.contentOrNull), detail.take(600))
            }
        }
    }

    private fun parseAspectStatus(raw: String?): VisionAspectStatus {
        val value = raw?.trim()?.lowercase().orEmpty()
        return when {
            // 必须先判断否定形式："not passed" 也包含 "pass"，"未通过" 也包含 "通过"。
            NEGATIVE_ASPECT_PATTERN.containsMatchIn(value) -> VisionAspectStatus.FAILED
            value.contains("pass") || value.contains("通过") -> VisionAspectStatus.PASSED
            else -> VisionAspectStatus.UNKNOWN
        }
    }

    private fun parseVerdict(raw: String?): IqJudgeVerdict? = when (raw?.trim()?.lowercase()) {
        "passed" -> IqJudgeVerdict.PASSED
        "incorrect" -> IqJudgeVerdict.INCORRECT
        "review" -> IqJudgeVerdict.REVIEW
        else -> null
    }

    private fun failure(reason: String) = VisionAssessment(
        verdict = IqJudgeVerdict.REVIEW,
        score = null,
        reason = reason.take(500),
    )

    private companion object {
        val ASPECT_KEYS = listOf("鹈鹕特征", "自行车结构", "骑行关系", "动画连贯性", "画面完成度")
        val NEGATIVE_ASPECT_PATTERN = Regex(
            "fail|incorrect|not\\s+(?:fully\\s+|yet\\s+)?pass(?:ed)?|" +
                "(?:does|did|is|was|has|have|had)\\s+not\\s+pass(?:ed)?|" +
                "never\\s+pass(?:ed)?|未通过|不通过|没通过|没有通过",
        )
    }
}
