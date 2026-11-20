package com.andharness.ru.domain.llm

import com.andharness.ru.domain.agent.LlmToolCall
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * Reassembles the streamed `choices[0].delta.tool_calls[]` fragments of one completion into whole
 * tool calls.
 *
 * A faithful port of the accumulator the opencode harness uses for OpenAI-compatible chat
 * (`packages/ai/src/protocols/openai-chat.ts`), because real providers break the spec in the same
 * ways there:
 *
 *  - **`index` may be missing.** A chunk without it used to be dropped, so the model "called" a tool
 *    and nothing ran. Here the key is recovered from the call id, then from the batch position, then
 *    from the last call seen.
 *  - **`id`/`name` may arrive AFTER the arguments.** Such a fragment is buffered in [pending] until
 *    its identity shows up, instead of being thrown away.
 *  - **Parallel calls** in one chunk are distinguished by batch position when the provider omits
 *    indexes entirely.
 *
 * [finish] drops a fragment that never learned its name (an unusable call) and gives an id to one
 * that only lacks that. Order is by stream index, so the same stream always yields the same list.
 *
 * Pure Kotlin, no Android: unit-tested on the JVM.
 */
class ToolCallStreamAccumulator {

    private class Pending {
        var id: String? = null
        var name: String? = null
        val args = StringBuilder()
    }

    private val tools = LinkedHashMap<Int, Pending>()
    private val pending = LinkedHashMap<Int, Pending>()
    private var latestIndex = -1
    private var nextIndex = 0

    /** True when no fragment has been received yet. */
    val isEmpty: Boolean
        get() = tools.isEmpty() && pending.isEmpty()

    /**
     * Adds one element of `delta.tool_calls`. [batchSize] is how many elements that delta carried:
     * it decides the fallback key when the provider sends no `index`. Returns true when the call
     * carried any new content (id, name or argument text), so the caller can refresh its watchdog.
     */
    fun append(delta: JsonObject, position: Int, batchSize: Int): Boolean {
        val function = delta["function"] as? JsonObject
        val explicitIndex = (delta["index"] as? JsonPrimitive)?.content?.toIntOrNull()
        val id = (delta["id"] as? JsonPrimitive)?.content?.takeIf { it.isNotEmpty() }
        val name = (function?.get("name") as? JsonPrimitive)?.content?.takeIf { it.isNotEmpty() }
        val fragment = (function?.get("arguments") as? JsonPrimitive)?.content.orEmpty()

        val matched = id?.let { callId ->
            tools.entries.firstOrNull { it.value.id == callId }?.key
                ?: pending.entries.firstOrNull { it.value.id == callId }?.key
        }
        val fallback = if (batchSize > 1) position else if (latestIndex >= 0) latestIndex else position
        val fallbackId = (tools[fallback] ?: pending[fallback])?.id
        val index = explicitIndex
            ?: matched
            ?: if (id != null && fallbackId != null && fallbackId != id) nextIndex else fallback

        val current = tools[index]
        val buffered = pending[index]
        val resolvedId = current?.id ?: buffered?.id ?: id
        val resolvedName = current?.name ?: buffered?.name ?: name
        // A later chunk appends to what this call already collected; the buffered fragment (if the
        // identity was late) is the base when nothing is registered yet.
        val text = ((current?.args ?: buffered?.args)?.toString() ?: "") + fragment

        latestIndex = index
        if (index + 1 > nextIndex) nextIndex = index + 1

        // A fragment without a name cannot be executed yet, so it waits for the chunk that names it.
        // An id may arrive late too — [finish] synthesizes one when it never does.
        if (current == null && resolvedName == null) {
            val entry = buffered ?: Pending().also { pending[index] = it }
            entry.id = resolvedId
            entry.name = resolvedName
            entry.args.setLength(0)
            entry.args.append(text)
            return resolvedId != null || fragment.isNotEmpty()
        }

        pending.remove(index)
        val target = current ?: Pending().also { tools[index] = it }
        target.id = resolvedId
        target.name = resolvedName
        target.args.setLength(0)
        target.args.append(text)
        return resolvedId != null || resolvedName != null || fragment.isNotEmpty()
    }

    /**
     * The complete calls, ordered by stream index, and resets the accumulator for the next attempt.
     * A call whose name never arrived cannot be executed and is dropped; a missing id is replaced
     * with a stable synthetic one, because the id only has to round-trip through the tool message.
     */
    fun finish(): List<LlmToolCall> {
        val calls = tools.entries
            .sortedBy { it.key }
            .mapNotNull { (index, entry) ->
                val name = entry.name ?: return@mapNotNull null
                LlmToolCall(
                    id = entry.id ?: "stream-call-$index",
                    name = name,
                    arguments = entry.args.toString(),
                )
            }
        clear()
        return calls
    }

    /** Drops everything collected so far, e.g. when a retry starts a fresh attempt. */
    fun clear() {
        tools.clear()
        pending.clear()
        latestIndex = -1
        nextIndex = 0
    }
}
