/**
 * Regression: a fork is one unit of work, and its branch histories merge in declaration order.
 *
 * Pins two invariants that a crash in the middle of a fan-out used to violate:
 *
 * 1. The durable cursor never points inside a branch. Branch states are not checkpointed, so
 *    recovery re-enters at the fork node and re-runs every branch, rather than resuming one
 *    branch with its siblings lost.
 * 2. Branch histories merge in fork-target declaration order — alpha, beta, gamma — not in
 *    whichever order the branches happened to finish.
 *
 * The three branches are deliberately uneven in length: beta asks for three things and alpha and
 * gamma for one each, so beta reliably finishes last. Declaration order and completion order
 * therefore disagree on every run, which is what gives the ordering assertion something to bite
 * on. Each branch is also told to prefix its answer with a fixed marker, so a missing branch is
 * visible in the merged text rather than inferred from its length.
 *
 * Run:
 *   hensu run regression/regression-fork-recovery -d working-dir -v --no-daemon \
 *     -c '{"subject": "cache invalidation"}'
 *
 * The CLI settles the structural half: 5 steps (fork, three branches, join), SUCCESS, and an
 * ALPHA/BETA/GAMMA block for every branch. Merge order and the durable cursor are read from the
 * server and the database instead — see exercise H of the manual test plan.
 */
fun regressionForkRecovery() = workflow("regression-fork-recovery") {
    description = "Fork recovery and history merge order — one unit of work, declaration order"
    version = "1.0.0"

    agents {
        agent("analyst") {
            role = "Terse analyst. Answer in one short paragraph, and always begin your answer " +
                "with the marker word the prompt gives you."
            model = Models.GEMINI_3_1_FLASH_LITE
            temperature = 0.0
        }
    }

    state {
        input("subject", VarType.STRING)
        variable("alpha", VarType.STRING, "first branch finding — declared first")
        variable("beta", VarType.STRING, "second branch finding — declared second")
        variable("gamma", VarType.STRING, "third branch finding — declared third")
        variable("merged", VarType.STRING, "concatenation of all three branch findings")
    }

    graph {
        start at "fan-out"

        // Declaration order is the contract under test. Do not reorder these targets
        // without also changing what the exercise asserts.
        fork("fan-out") {
            targets("branch-alpha", "branch-beta", "branch-gamma")
            onComplete goto "fan-in"
        }

        node("branch-alpha") {
            agent = "analyst"
            prompt = "State one risk of {subject}. Begin your answer with the word ALPHA."
            writes("alpha")
            onSuccess goto "fan-in"
        }

        // The long branch. Three requirements rather than one, so this branch finishes after
        // the other two and completion order cannot be mistaken for declaration order.
        node("branch-beta") {
            agent = "analyst"
            prompt = """
                For {subject}, state one mitigation, then name the team that would own it,
                then say what evidence would show the mitigation is working.
                Begin your answer with the word BETA.
            """.trimIndent()
            writes("beta")
            onSuccess goto "fan-in"
        }

        node("branch-gamma") {
            agent = "analyst"
            prompt = "State one open question about {subject}. Begin your answer with the word GAMMA."
            writes("gamma")
            onSuccess goto "fan-in"
        }

        join("fan-in") {
            await("fan-out")
            mergeStrategy = MergeStrategy.CONCATENATE
            exports("alpha", "beta", "gamma")
            writes("merged")
            timeout = 60000
            // A branch lost to a bad recovery must surface as a failure here rather than as a
            // quietly short merge — that silent truncation was the original defect.
            failOnError = true
            onSuccess goto "complete"
            onFailure retry 0 otherwise "incomplete"
        }

        end("complete", ExitStatus.SUCCESS)
        end("incomplete", ExitStatus.FAILURE)
    }
}
