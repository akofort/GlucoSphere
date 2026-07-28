package com.example.diabai.domain.llm

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject

/**
 * Safe alternatives to kotlinx.serialization's `.jsonObject`/`.jsonArray` extension getters, which
 * throw `IllegalArgumentException("Element class kotlinx.serialization.json.JsonNull is not a
 * JsonObject")` (or `JsonArray`) whenever the element is present but its JSON value is literally
 * `null` -- `?.jsonObject` only guards against the KEY being *absent* (Kotlin `null`); a key that
 * IS present with a JSON `null` value still reaches the unsafe getter and crashes. This is not
 * malformed input: the OpenAI-compatible streaming spec explicitly sends `"usage": null` on every
 * non-final chunk once `stream_options.include_usage` is requested (see [OpenAiApiProvider]), and
 * DeepSeek/OpenRouter also send explicit `null` for absent `tool_calls`/`function` sub-objects
 * rather than omitting the key -- this crashed the DeepSeek provider on literally the first
 * streamed chunk of every turn.
 *
 * `as? JsonObject`/`as? JsonArray` already handles this correctly on their own (a [JsonElement]
 * that's actually `JsonNull` simply isn't a `JsonObject`/`JsonArray`, so the safe cast yields
 * `null` instead of throwing) -- these just give it a readable name so call sites read the same
 * as the unsafe originals (`chunk["usage"]?.jsonObjectOrNull` vs. `chunk["usage"]?.jsonObject`).
 * `.jsonPrimitive` chains elsewhere are NOT at risk the same way: `JsonNull` itself IS-A
 * `JsonPrimitive` in kotlinx.serialization, and `.contentOrNull`/`.intOrNull` etc. already special
 * case it to Kotlin `null` rather than throwing.
 */
val JsonElement.jsonObjectOrNull: JsonObject? get() = this as? JsonObject
val JsonElement.jsonArrayOrNull: JsonArray? get() = this as? JsonArray
