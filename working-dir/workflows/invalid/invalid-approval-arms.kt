/**
 * Negative case: a review node that declares one approval arm must declare the other.
 *
 * With `onApproval` present and `onRejection` absent and nothing to absorb the fall-through, a
 * rejection has nowhere to go. The workflow used to end at the review node itself, which reads
 * as a completed run rather than as a routing hole. The build refuses instead.
 *
 * This file is expected to FAIL. It is a fixture, not an example.
 *
 * Run: hensu build invalid/invalid-approval-arms -d working-dir
 * Expect: build failure — declares an approval arm but no 'onRejection' transition.
 */
fun invalidApprovalArms() = workflow("invalid-approval-arms") {
    description = "Fixture: approval arm without its rejection counterpart"
    version = "1.0.0"

    agents {
        agent("analyst") {
            role = "Analyst"
            model = Models.GEMINI_3_1_FLASH_LITE
        }
    }

    state {
        input("application", VarType.STRING)
        variable("assessment", VarType.STRING, "the analyst's assessment")
    }

    graph {
        start at "assess"

        node("assess") {
            agent = "analyst"
            prompt = "Assess this application: {application}."
            writes("assessment")

            review {
                mode = ReviewMode.REQUIRED
                allowBacktrack = false
            }

            onApproval goto "accepted"
            // No onRejection, and no catch-all below to absorb it.
        }

        end("accepted", ExitStatus.SUCCESS)
    }
}
