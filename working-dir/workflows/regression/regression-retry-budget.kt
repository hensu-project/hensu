/**
 * Regression: a bounded revise loop spends exactly its budget, and the counter is durable.
 *
 * `revise "work" retry 3 otherwise "escalate"` means the node runs once, then is revised at most
 * three times, then escalates — four executions in total. The counter lives in HensuState under
 * `<namespace>:<nodeId>` and travels through HensuSnapshot, so a crash mid-loop must resume with
 * the budget partly spent rather than reset.
 *
 * Determinism comes from the task itself rather than from a stub. The node is asked to do
 * something it cannot finish — the input names a document it has never been given — and is told
 * to report `in-progress` while anything is missing. A live agent that follows its instructions
 * therefore never reports `complete`, budget exhaustion is the only reachable outcome, and the
 * attempt count is exact. If a run does end at `done`, the agent claimed to have finished work it
 * had no input for; re-run it before reading anything into the result.
 *
 * Run:
 *   hensu run regression/regression-retry-budget -d working-dir -v --no-daemon \
 *     -c '{"task": "reconcile the Q3 ledger against the bank statement"}'
 *
 * Expect: node `work` executes 4 times, then the workflow ends at `escalate` (FAILURE).
 */
fun regressionRetryBudget() = workflow("regression-retry-budget") {
    description = "Bounded revise budget — exact attempt count and durable retry counters"
    version = "1.0.0"

    agents {
        agent("worker") {
            role = "Meticulous task executor. You never claim a task is finished while any " +
                "required input is missing. Return JSON with keys: result, status " +
                "(complete | blocked | in-progress), recommendation."
            model = Models.GEMINI_3_1_FLASH_LITE
            temperature = 0.0
        }
    }

    state {
        input("task", VarType.STRING)
        variable("result", VarType.STRING, "current work product")
        variable("status", VarType.STRING, "self-reported state: complete, blocked, or in-progress")
    }

    graph {
        start at "work"

        node("work") {
            agent = "worker"
            prompt = """
                Work on this task: {task}.

                You have not been given the source documents, and you cannot request them.
                Report what you were able to establish without them.

                Report status "complete" only when the task is fully done with the documents in
                hand. While any required input is missing, report "in-progress" and say in
                recommendation which document you still need. Do not report "blocked" — a missing
                document is an incomplete task, not an impassable one.
            """.trimIndent()
            writes("result", "status")

            onCondition("status") {
                whenValue equalTo "complete" goto "done"
                // Budget of 3 revisions on top of the initial run. The escalation target is
                // reached only once the counter has reached the budget, which is the arithmetic
                // under test.
                otherwise revise "work" retry 3 otherwise "escalate"
            }
        }

        end("done", ExitStatus.SUCCESS)
        end("escalate", ExitStatus.FAILURE)
    }
}
