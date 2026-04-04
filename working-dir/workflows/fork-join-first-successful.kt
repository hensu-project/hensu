/**
 * Example: Fork-Join with FIRST_SUCCESSFUL merge strategy
 *
 * This workflow demonstrates:
 * - Multiple parallel translation attempts using different approaches
 * - FIRST_SUCCESSFUL picks the first successful branch in definition order
 * - Useful when you want redundancy – multiple attempts, take the first that works
 *
 * Flow:
 *   start → prepare → fork(literal, idiomatic, creative) → join → polish → success
 *
 * Use case: Translate a technical paragraph into Japanese using three different
 * translation strategies. The first successful result (in definition order) is used.
 */
fun workflow() = workflow("fork-join-first-successful") {
    description = "Demonstrates FIRST_SUCCESSFUL merge – redundant translation attempts"

    agents {
        agent("preparer") {
            model = Models.GEMINI_3_1_FLASH_LITE
            role = "Translation preparer"
        }
        agent("translator") {
            model = Models.GEMINI_3_1_FLASH_LITE
            role = "Translation specialist"
        }
        agent("editor") {
            model = Models.GEMINI_3_1_FLASH_LITE
            role = "Translation editor"
        }
    }

    state {
        variable("source_text",       VarType.STRING, "original text to translate")
        variable("context_notes",     VarType.STRING, "contextual notes for translators")
        variable("literal_result",    VarType.STRING, "literal word-for-word translation")
        variable("idiomatic_result",  VarType.STRING, "idiomatic natural-sounding translation")
        variable("creative_result",   VarType.STRING, "creative adapted translation")
        variable("best_translation",  VarType.STRING, "first successful translation result")
        variable("final_translation", VarType.STRING, "polished final translation")
    }

    graph {
        start at "prepare"

        node("prepare") {
            agent = "preparer"
            prompt = """
                Prepare the following English text for Japanese translation.
                Identify cultural references, idioms, and technical terms that need special handling.

                Text: "Structured concurrency in Java 25 ensures that virtual thread lifecycles
                are scoped to their parent task, preventing resource leaks and orphaned computations."
            """.trimIndent()
            writes("source_text", "context_notes")
            onSuccess goto "fork-translate"
        }

        fork("fork-translate") {
            targets("translate-literal", "translate-idiomatic", "translate-creative")
            onComplete goto "pick-best"
        }

        node("translate-literal") {
            agent = "translator"
            prompt = """
                Translate the following text to Japanese using a literal, word-for-word approach.
                Preserve technical terminology exactly.

                Text: {source_text}
                Context: {context_notes}
            """.trimIndent()
            writes("literal_result")
            onSuccess goto "pick-best"
        }

        node("translate-idiomatic") {
            agent = "translator"
            prompt = """
                Translate the following text to Japanese using natural, idiomatic Japanese.
                Adapt sentence structure to feel native while keeping technical accuracy.

                Text: {source_text}
                Context: {context_notes}
            """.trimIndent()
            writes("idiomatic_result")
            onSuccess goto "pick-best"
        }

        node("translate-creative") {
            agent = "translator"
            prompt = """
                Translate the following text to Japanese with creative adaptation.
                Make it engaging for a Japanese developer audience, using familiar analogies.

                Text: {source_text}
                Context: {context_notes}
            """.trimIndent()
            writes("creative_result")
            onSuccess goto "pick-best"
        }

        join("pick-best") {
            await("fork-translate")
            mergeStrategy = MergeStrategy.FIRST_SUCCESSFUL
            writes("best_translation")
            timeout = 30000
            failOnError = false
            onSuccess goto "polish"
            onFailure retry 0 otherwise "failed"
        }

        node("polish") {
            agent = "editor"
            prompt = """
                Review and polish this Japanese translation for accuracy and readability:

                {best_translation}

                Fix any grammatical issues and ensure technical terms are correctly rendered.
            """.trimIndent()
            writes("final_translation")
            onSuccess goto "success"
        }

        end("success")
        end("failed", ExitStatus.FAILURE)
    }
}
