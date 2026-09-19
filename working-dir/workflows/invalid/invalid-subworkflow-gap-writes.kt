/**
 * Negative case: a sub-workflow node writing an engine-owned gap key must be refused at build time.
 *
 * `invalid-capability-gap-writes.kt` covers the same rule for a standard node. A sub-workflow node
 * reaches the parent state through its output mapping instead of through `writes`, and the parent
 * state is where `_capability_gaps` lives – so a child free to write that key could hand the parent
 * a value that erases the record of what the parent's own run was refused.
 *
 * Two layers refuse this, and the fixture proves the outer one. `SubWorkflowNodeBuilder.writes`
 * rejects the name as the DSL is built, which is the path this file takes.
 * `WorkflowValidator.validateSubWorkflow` rejects the same mapping when a workflow is constructed
 * some other way – deserialized, or assembled in Java – and that layer is covered by a unit test,
 * because no `.kt` fixture can reach it.
 *
 * This file is expected to FAIL. It is a fixture, not an example.
 *
 * Run: hensu build invalid/invalid-subworkflow-gap-writes -d working-dir
 * Expect: build failure naming `_capability_gaps` and pointing at
 * `onCondition("_capability_gap_count")` as the supported way to route on a refusal.
 */
fun invalidSubWorkflowGapWrites() = workflow("invalid-subworkflow-gap-writes") {
    description = "Fixture: a sub-workflow node writing an engine-owned capability gap key"
    version = "1.0.0"

    agents {
        agent("delegator") {
            role = "Delegator"
            model = Models.GEMINI_3_1_FLASH_LITE
        }
    }

    state {
        input("draft", VarType.STRING)
    }

    graph {
        start at "delegate"

        subWorkflow("delegate") {
            target = "sub-summarizer"
            imports("draft")
            writes("_capability_gaps")
            onSuccess goto "done"
        }

        end("done", ExitStatus.SUCCESS)
    }
}
