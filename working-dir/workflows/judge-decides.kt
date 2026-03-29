/**
 * Example of JUDGE_DECIDES consensus strategy with yields.
 *
 * This workflow demonstrates:
 * - Parallel execution of competing proposal writers
 * - A senior judge agent that reviews all branch outputs and picks the winner
 * - Branch yields: each proposer produces a domain artifact (proposal_text)
 * - Only the judge-selected winner's yields merge into context
 * - Downstream node refines the winning proposal
 *
 * Unlike MAJORITY_VOTE where branches vote on shared content,
 * JUDGE_DECIDES is ideal when branches produce competing alternatives
 * and an expert must choose the best one.
 */
fun workflow() = workflow("judge-decides-test") {
    description = "Judge-based consensus: competing proposals evaluated by a senior agent"

    agents {
        agent("proposer1") {
            model = Models.GEMINI_3_1_FLASH_LITE
            role = "Creative proposal writer focusing on innovation"
        }
        agent("proposer2") {
            model = Models.GEMINI_3_1_FLASH_LITE
            role = "Practical proposal writer focusing on feasibility"
        }
        agent("proposer3") {
            model = Models.GEMINI_3_1_FLASH_LITE
            role = "Risk-aware proposal writer focusing on safety"
        }
        agent("judge") {
            model = Models.GEMINI_3_1_PRO
            role = "Senior technical lead who evaluates competing proposals and selects the best approach"
        }
        agent("refiner") {
            model = Models.GEMINI_3_1_PRO
            role = "Technical writer who polishes the selected proposal"
        }
    }

    state {
        variable("topic", VarType.STRING, "the technical topic for proposals")
        variable("proposal_innovative", VarType.STRING, "innovation-focused proposal")
        variable("proposal_practical", VarType.STRING, "feasibility-focused proposal")
        variable("proposal_safe", VarType.STRING, "safety-focused proposal")
        variable("final_proposal", VarType.STRING, "the refined final proposal")
    }

    graph {
        start at "set-topic"

        node("set-topic") {
            agent = "proposer1"
            prompt = "In one sentence, state the topic: 'How should a startup approach building its first CI/CD pipeline?'. Return it as the topic field."
            writes("topic")
            onSuccess goto "proposals"
        }

        // Parallel competing proposals - judge picks the winner
        parallel("proposals") {
            branch("innovative") {
                agent = "proposer1"
                prompt = """
                    Write a short proposal (2-3 sentences) on: {topic}
                    Focus on innovative, cutting-edge approaches.
                """.trimIndent()
                yields("proposal_innovative")
            }

            branch("practical") {
                agent = "proposer2"
                prompt = """
                    Write a short proposal (2-3 sentences) on: {topic}
                    Focus on practical, battle-tested approaches.
                """.trimIndent()
                yields("proposal_practical")
            }

            branch("safe") {
                agent = "proposer3"
                prompt = """
                    Write a short proposal (2-3 sentences) on: {topic}
                    Focus on risk mitigation and reliability.
                """.trimIndent()
                yields("proposal_safe")
            }

            consensus {
                strategy = ConsensusStrategy.JUDGE_DECIDES
                judge = "judge"
            }

            onConsensus goto "refine"
            onNoConsensus goto "rejected"
        }

        // Refine the winning proposal
        node("refine") {
            agent = "refiner"
            prompt = """
                You are refining the winning proposal selected by the judge.

                Topic: {topic}

                Winning proposal (innovative): {proposal_innovative}
                Winning proposal (practical): {proposal_practical}
                Winning proposal (safe): {proposal_safe}

                Note: only the winning branch's yield is populated above.
                Produce a polished final version of the available proposal.
            """.trimIndent()
            writes("final_proposal")
            onSuccess goto "approved"
        }

        end("approved", ExitStatus.SUCCESS)
        end("rejected", ExitStatus.FAILURE)
    }
}
