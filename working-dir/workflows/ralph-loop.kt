/**
 * Ralph loop on a single node using condition-based routing (new onCondition syntax).
 *
 * The worker re-executes itself until it self-reports completion: each iteration the
 * agent returns a status variable, the exit arm leaves the loop on "complete", a
 * blocked task escalates immediately, and anything else revises the worker with the
 * previous iteration's recommendation injected as feedback — up to 5 attempts, then
 * escalation. No plan subsystem, no loop node: iteration is just transitions.
 *
 * Run: hensu run ralph-loop -d working-dir -v -c "{\"task\": \"Summarize the Q2 report\"}"
 *      -v (verbose) shows node inputs and outputs in the console
 */
fun ralphLoop() = workflow("ralph-loop") {
    description = "Single-node ralph loop: iterate until the agent reports status complete"
    version = "1.0.0"

    agents {
        agent("worker") {
            role = "Diligent task executor. Work on the task, judge your own progress. " +
                "Return JSON with keys: result, status (complete | blocked | in-progress), " +
                "recommendation (what to improve next iteration)."
            model = Models.GEMINI_3_1_FLASH_LITE
            temperature = 0.4
        }
    }

    state {
        input("task", VarType.STRING)
        variable("result", VarType.STRING, "current work product, refined each iteration")
        variable("status", VarType.STRING, "self-reported task state: complete, blocked, or in-progress")
    }

    graph {
        start at "work"

        node("work") {
            agent  = "worker"
            prompt = "Work on this task: {task}."
            writes("result", "status")

            // Exit arms first — ordering is load-bearing (first match wins).
            // A notEqualTo arm would overlap equalTo "blocked" (build error);
            // the otherwise else-arm covers every remaining value instead.
            onCondition("status") {
                whenValue equalTo "complete" goto "done"
                whenValue equalTo "blocked" goto "escalate"
                otherwise revise "work" retry 5 otherwise "escalate"
            }
        }

        end("done", ExitStatus.SUCCESS)
        end("escalate", ExitStatus.FAILURE)
    }
}
