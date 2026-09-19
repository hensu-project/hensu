/**
 * Negative case: `writes("_capability_gaps")` must be refused at build time.
 *
 * `_capability_gaps` is written by the engine while the node runs – the tool loop appends one
 * record per refused call. A node that also declares it in `writes` makes the agent's own JSON a
 * second writer for the same key, which lets a blocked agent overwrite the record of what it was
 * refused with anything it likes. Refusing the declaration is what keeps a gap record something the
 * agent cannot author.
 *
 * There is deliberately no `state {}` block. The load-time check in `WorkflowValidator` walks the
 * declared state, so it never sees this node; the builder's own check is the only thing that can
 * refuse it, which is exactly the path this fixture covers.
 *
 * This file is expected to FAIL. It is a fixture, not an example.
 *
 * Run: hensu build invalid/invalid-capability-gap-writes -d working-dir
 * Expect: build failure naming `_capability_gaps` and pointing at
 * `onCondition("_capability_gap_count")` as the supported way to route on a refusal.
 */
fun invalidCapabilityGapWrites() = workflow("invalid-capability-gap-writes") {
    description = "Fixture: writes() on an engine-owned capability gap key"
    version = "1.0.0"

    agents {
        agent("announcer") {
            role = "Announcer"
            model = Models.GEMINI_3_1_FLASH_LITE
        }
    }

    graph {
        start at "announce"

        node("announce") {
            agent = "announcer"
            prompt = "Announce something."
            writes("_capability_gaps")
            onSuccess goto "done"
        }

        end("done", ExitStatus.SUCCESS)
    }
}
