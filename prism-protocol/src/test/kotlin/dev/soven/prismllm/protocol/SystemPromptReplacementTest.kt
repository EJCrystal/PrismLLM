package dev.soven.prismllm.protocol

import dev.soven.prismllm.shared.domain.GatewayProtocol
import org.junit.jupiter.api.Tag
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull

@Tag("unit")
class SystemPromptReplacementTest {
    private fun body(source: String) = Json.parseToJsonElement(source).jsonObject
    private val prompt = "  You are ZCode, an interactive agent.\n保留正文空白。  "

    @Test
    fun chatRemovesAllSystemAndDeveloperMessagesWithoutTouchingConversationOrTools() {
        val original = body("""{"messages":[{"role":"system","content":"old one"},{"role":"user","content":"hello"},{"role":"developer","content":[{"type":"text","text":"old two"}]},{"role":"assistant","tool_calls":[{"id":"call_one"}]},{"role":"tool","tool_call_id":"call_one","content":"result"}],"tools":[{"type":"function","function":{"name":"read_file"}}],"stream":true}""")
        val rewritten = replaceSystemPrompt(original, GatewayProtocol.CHAT_COMPLETIONS, prompt)
        val messages = rewritten["messages"]!!.jsonArray
        assertEquals(prompt, messages.first().jsonObject.string("content"))
        assertEquals(listOf("system", "user", "assistant", "tool"), messages.map { it.jsonObject.string("role") })
        assertEquals(original["tools"], rewritten["tools"])
        assertEquals(original["stream"], rewritten["stream"])
        assertEquals(original["messages"]!!.jsonArray[1], messages[1])
        assertEquals(5, original["messages"]!!.jsonArray.size)
        assertEquals(rewritten, replaceSystemPrompt(rewritten, GatewayProtocol.CHAT_COMPLETIONS, prompt))
    }

    @Test
    fun messagesReplacesTheWholeSystemBlockAndItsCacheMetadata() {
        val original = body("""{"system":[{"type":"text","text":"old","cache_control":{"type":"ephemeral"}}],"messages":[{"role":"user","content":"hi"}],"tools":[],"max_tokens":32}""")
        val rewritten = replaceSystemPrompt(original, GatewayProtocol.MESSAGES, prompt)
        assertEquals(JsonPrimitive(prompt), rewritten["system"])
        assertEquals(JsonObject(original - "system"), JsonObject(rewritten - "system"))
    }

    @Test
    fun responsesClearsInstructionsAndInputSystemRolesButKeepsToolsAndContinuation() {
        val original = body("""{"instructions":"old","previous_response_id":"resp_previous","input":[{"role":"developer","content":"old developer"},{"role":"system","content":"old system"},{"role":"user","content":"hi"},{"type":"function_call_output","call_id":"call_one","output":"ok"}],"tools":[{"type":"function","name":"read_file"}]}""")
        val rewritten = replaceSystemPrompt(original, GatewayProtocol.RESPONSES, prompt)
        assertEquals(JsonPrimitive(prompt), rewritten["instructions"])
        assertEquals(JsonArray(original["input"]!!.jsonArray.drop(2)), rewritten["input"])
        assertEquals(original["tools"], rewritten["tools"])
        assertEquals(original["previous_response_id"], rewritten["previous_response_id"])
        val stringInput = replaceSystemPrompt(body("""{"input":"hello"}"""), GatewayProtocol.RESPONSES, prompt)
        assertEquals(JsonPrimitive("hello"), stringInput["input"])
    }

    @Test
    fun geminiReplacesAllPartsAndLeavesContentsAndConfigurationUntouched() {
        val original = body("""{"systemInstruction":{"parts":[{"text":"old one"},{"text":"old two"}]},"system_instruction":{"parts":[{"text":"old alias"}]},"contents":[{"role":"user","parts":[{"text":"hello"}]}],"tools":[],"generationConfig":{"temperature":0.3}}""")
        val rewritten = replaceSystemPrompt(original, GatewayProtocol.GEMINI, prompt)
        val instruction = rewritten["systemInstruction"]!!.jsonObject["parts"]!!.jsonArray.single().jsonObject
        assertEquals(prompt, instruction.string("text"))
        assertFalse("system_instruction" in rewritten)
        assertEquals(original["contents"], rewritten["contents"])
        assertEquals(original["generationConfig"], rewritten["generationConfig"])
        assertEquals(original["tools"], rewritten["tools"])
    }

    @Test
    fun everyProtocolCanAddMissingInstructionsAndInvalidChatMessagesAreNotSilentlyDropped() {
        for (protocol in GatewayProtocol.entries) {
            assertNotNull(replaceSystemPrompt(body("{}"), protocol, prompt))
        }
        val error = assertFailsWith<ProtocolConversionException> {
            replaceSystemPrompt(body("""{"messages":"invalid"}"""), GatewayProtocol.CHAT_COMPLETIONS, prompt)
        }
        assertEquals("messages", error.field)
    }

    @Test
    fun geminiCachedContextIsRejectedBeforeAddingIncompatibleInstructionsWithoutDroppingContext() {
        for (field in listOf("cachedContent", "cached_content")) {
            val original = body("""{"contents":[{"role":"user","parts":[{"text":"continue"}]}],"$field":"cachedContents/context-one"}""")
            val before = original.toString()
            val error = assertFailsWith<ProtocolConversionException> {
                replaceSystemPrompt(original, GatewayProtocol.GEMINI, prompt)
            }
            assertEquals(field, error.field)
            assertEquals(before, original.toString())
            assertEquals(JsonPrimitive("cachedContents/context-one"), original[field])
            assertFalse("systemInstruction" in original)
        }
        for (source in listOf("{}", """{"cachedContent":null}""", """{"cachedContent":""}""")) {
            val rewritten = replaceSystemPrompt(body(source), GatewayProtocol.GEMINI, prompt)
            assertNotNull(rewritten["systemInstruction"])
        }
    }
}
