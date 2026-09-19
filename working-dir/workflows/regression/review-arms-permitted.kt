/**
 * Positive case: the two review shapes that validation must NOT reject.
 *
 * Both-arms validation is easy to make too strict, and an over-strict version would break
 * workflows that were always valid. This file is the guard against that, and it is expected to
 * BUILD:
 *
 * - `gate-with-fallthrough` declares `onApproval` and no `onRejection`, but a catch-all
 *   `onSuccess` follows it. The rejection is absorbed rather than stranded, so the graph has no
 *   hole. A warning about the fall-through is appropriate; a build failure is not.
 * - `gate-no-arms` declares no approval arm at all. Rejection is terminal there by design —
 *   there is nothing to route because the author asked for nothing to be routed.
 *
 * Read together with the invalid-approval-arms fixture: that one is the same shape as
 * `gate-with-fallthrough` minus the catch-all, which is precisely the difference between a
 * routing hole and a deliberate fall-through.
 *
 * Run: hensu build regression/review-arms-permitted -d working-dir
 * Expect: success, with a logged warning about the fall-through on `gate-with-fallthrough`.
 */
fun reviewArmsPermitted() = workflow("review-arms-permitted") {
    description = "Guard: review shapes that both-arms validation must accept"
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
        variable("summary", VarType.STRING, "condensed assessment")
    }

    graph {
        start at "gate-with-fallthrough"

        node("gate-with-fallthrough") {
            agent = "analyst"
            prompt = "Assess this application: {application}."
            writes("assessment")

            review {
                mode = ReviewMode.REQUIRED
                allowBacktrack = false
            }

            onApproval goto "gate-no-arms"
            // Catch-all: absorbs the rejection the approval arm leaves unrouted.
            onSuccess goto "gate-no-arms"
        }

        node("gate-no-arms") {
            agent = "analyst"
            prompt = "Summarize the assessment: {assessment}."
            writes("summary")

            review {
                mode = ReviewMode.OPTIONAL
                allowBacktrack = true
            }

            onSuccess goto "done"
        }

        end("done", ExitStatus.SUCCESS)
    }
}
