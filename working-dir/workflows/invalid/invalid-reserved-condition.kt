/**
 * Negative case: `onCondition("approved")` must be refused at build time.
 *
 * Routing on the engine's approval variable bypasses the approval transition, and with it the
 * rule that a human verdict outranks the agent's own `approved` value. A workflow written this
 * way would look correct and would quietly ignore its reviewer — the exact defect this PR fixes.
 * The build error points the author at onApproval/onRejection instead.
 *
 * This file is expected to FAIL. It is a fixture, not an example.
 *
 * Run: hensu build invalid/invalid-reserved-condition -d working-dir
 * Expect: build failure naming `approved` as reserved, and directing to onApproval/onRejection.
 */
fun invalidReservedCondition() = workflow("invalid-reserved-condition") {
    description = "Fixture: onCondition() on a reserved engine variable"
    version = "1.0.0"

    agents {
        agent("reviewer") {
            role = "Reviewer"
            model = Models.GEMINI_3_1_FLASH_LITE
        }
    }

    state {
        input("document", VarType.STRING)
        variable("notes", VarType.STRING, "reviewer notes")
    }

    graph {
        start at "assess"

        node("assess") {
            agent = "reviewer"
            prompt = "Assess {document}."
            writes("notes")

            onCondition("approved") {
                whenValue equalTo true goto "accepted"
                otherwise goto "declined"
            }
        }

        end("accepted", ExitStatus.SUCCESS)
        end("declined", ExitStatus.FAILURE)
    }
}
