package dev.soven.prismllm.protocol

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * 抬升 Responses 输入里的 `additional_tools` 项，参考 magpie 的 liftAdditionalTools。
 *
 * 背景：
 * Codex 对目录标记为 "Responses Lite" 的模型（ChatGPT 后端的 gpt-6 / gpt-5.6 等）使用一种
 * 精简请求形状：不发 `tools`、不发 `instructions`，所有工具作为 `input` 的第一项
 * `additional_tools` 发送（普通函数与 freeform 工具归入名为 "functions" 的 namespace），
 * prompt 作为其后的 developer 消息。只有 OpenAI 的后端认识该输入项；其它上游（包括本网关
 * 的跨协议转换）收到即 400。xAI 的后端对它报 422 "unknown item type additional_tools"
 * （magpie#350），Codex 客户端对该错误不重试，整轮请求直接失败。
 *
 * 处理规则（与 magpie 一致，选择显式改写而非拒绝）：
 * - `additional_tools` 项携带的工具合并进请求级 `tools` 数组：Chat 的工具本来就按请求声明，
 *   该映射是无损的；`"functions"` namespace 拆开平铺；
 * - 同名工具去重；同名 namespace 再次出现时把其中没有的工具追加进已有 namespace；
 * - 历史里模型对 Codex 自有工具的调用项带 `namespace` 标记，工具平铺后该标记不再是合法
 *   形状，一并去掉；
 * - 请求没有 `additional_tools` 时原样返回，零拷贝。
 *
 * 非 `additional_tools` 的输入项（含形状之外的非对象项）按原引用保留，不重排不丢失。
 *
 * @author 文杰
 * @since 2026-11-10
 */
internal const val LITE_FUNCTION_NAMESPACE = "functions"

/** 请求里有 `additional_tools` 时返回抬升后的副本，没有时原样返回。 */
internal fun liftAdditionalTools(request: JsonObject): JsonObject {
    val input = request["input"] as? JsonArray ?: return request
    if (input.none { (it as? JsonObject)?.string("type") == "additional_tools" }) return request

    val tools = (request["tools"] as? JsonArray)?.toMutableList() ?: mutableListOf()
    val byName = toolsByName(tools)
    val kept = mutableListOf<JsonElement>()
    var lifted = false
    input.forEach { element ->
        val item = element as? JsonObject
        if (item?.string("type") != "additional_tools") {
            // 模型对 Codex 自有工具的调用按分组时的 namespace 记录；工具平铺后它就是普通调用。
            kept += if (item != null && item.string("namespace") == LITE_FUNCTION_NAMESPACE) {
                JsonObject(item.filterKeys { it != "namespace" })
            } else {
                element
            }
            return@forEach
        }
        lifted = true
        (item["tools"] as? JsonArray)?.forEach { toolElement ->
            val tool = toolElement as? JsonObject ?: return@forEach
            if (tool.string("type") == "namespace" && tool.string("name") == LITE_FUNCTION_NAMESPACE) {
                (tool["tools"] as? JsonArray)?.forEach { nestedElement ->
                    (nestedElement as? JsonObject)?.let { addTool(tools, byName, it) }
                }
                return@forEach
            }
            addTool(tools, byName, tool)
        }
    }
    if (!lifted) return request

    return buildJsonObject {
        request.forEach { (key, value) ->
            when (key) {
                "input" -> put("input", JsonArray(kept))
                "tools" -> if (tools.isNotEmpty()) put("tools", JsonArray(tools))
                else -> put(key, value)
            }
        }
        if ("tools" !in request && tools.isNotEmpty()) put("tools", JsonArray(tools))
    }
}

/** 工具按名字索引；没有名字的宿主工具（如 `web_search`）按类型索引，非对象元素不参与匹配。 */
private fun toolsByName(tools: List<JsonElement>): MutableMap<String, JsonObject> {
    val have = LinkedHashMap<String, JsonObject>()
    tools.forEach { element ->
        (element as? JsonObject)?.let { have[toolKey(it)] = it }
    }
    return have
}

private fun toolKey(tool: JsonObject): String {
    val name = tool.string("name")
    return if (!name.isNullOrEmpty()) name else "#" + (tool.string("type") ?: "")
}

/** tools 追加 [tool]：新工具直接加入；同名 namespace 把其中没有的工具并入已有 namespace。 */
private fun addTool(tools: MutableList<JsonElement>, have: MutableMap<String, JsonObject>, tool: JsonObject) {
    val key = toolKey(tool)
    val existing = have[key]
    if (existing == null) {
        have[key] = tool
        tools += tool
        return
    }
    if (existing.string("type") != "namespace" || tool.string("type") != "namespace") return
    val nested = (existing["tools"] as? JsonArray)?.toMutableList() ?: mutableListOf()
    (tool["tools"] as? JsonArray)?.forEach { element ->
        val incoming = element as? JsonObject ?: return@forEach
        val name = incoming.string("name")
        if (name == null || nested.none { (it as? JsonObject)?.string("name") == name }) {
            nested += incoming
        }
    }
    val merged = JsonObject(existing + ("tools" to JsonArray(nested)))
    have[key] = merged
    val index = tools.indexOfFirst { (it as? JsonObject) == existing }
    if (index >= 0) tools[index] = merged
}
