/**
 * Negative case: `writes("approved")` must be refused at build time.
 *
 * `approved` is an engine variable — the engine infers it from the graph and extracts it itself.
 * A node that also declares it in `writes` creates two writers for one name, and the agent's
 * self-assessment silently competes with the engine's. Refusing the declaration is what keeps
 * "approved has exactly two writers" a fact rather than a convention.
 *
 * This file is expected to FAIL. It is a fixture, not an example.
 *
 * Run: hensu build invalid/invalid-reserved-writes -d working-dir
 * Expect: build failure naming `approved` as a reserved engine variable.
 */
fun invalidReservedWrites() = workflow("invalid-reserved-writes") {
    description = "Fixture: writes() on a reserved engine variable"
    version = "1.0.0"

    agents {
        agent("reviewer") {
            role = "Reviewer"
            model = Models.GEMINI_3_1_FLASH_LITE
        }
    }

    state {
        input("document", VarType.STRING)
        variable("approved", VarType.BOOLEAN, "illegal: collides with the engine variable")
    }

    graph {
        start at "assess"

        node("assess") {
            agent = "reviewer"
            prompt = "Assess {document}."
            writes("approved")
            onSuccess goto "done"
        }

        end("done", ExitStatus.SUCCESS)
    }
}
