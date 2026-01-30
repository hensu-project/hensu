package io.hensu.dsl.builders

/**
 * Predefined model shortcuts for use in agent definitions.
 *
 * Usage:
 * ```kotlin
 * agent("writer") {
 *     model = Models.CLAUDE_SONNET_4_5
 *     role = "content writer"
 * }
 * ```
 */
object Models {
    // Claude models
    const val CLAUDE_SONNET_4_5 = "claude-sonnet-4.5-20250514"
    const val CLAUDE_OPUS_4_1 = "claude-opus-4.1-20250514"
    const val CLAUDE_HAIKU_4_5 = "claude-haiku-4.5-20251001"

    // OpenAI models
    const val GPT_4 = "gpt-4"
    const val GPT_4_TURBO = "gpt-4-turbo"
    const val GPT_4O = "gpt-4o"

    // Google Gemini models
    const val GEMINI_3_FLASH = "gemini-3-flash"
    const val GEMINI_2_5_FLASH = "gemini-2.5-flash"
    const val GEMINI_2_5_PRO = "gemini-2.5-pro"

    // DeepSeek models
    const val DEEPSEEK_CHAT = "deepseek-chat"
    const val DEEPSEEK_CODER = "deepseek-coder"
}
