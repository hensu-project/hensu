/**
 * Example of UNANIMOUS consensus strategy with yields.
 *
 * This workflow demonstrates:
 * - Parallel execution of multiple security auditors
 * - Unanimous consensus: ALL branches must approve for consensus
 * - Branch yields: each auditor produces domain-specific findings
 * - All yields merge into context regardless of vote outcome
 * - Downstream node compiles the final security report
 *
 * Use UNANIMOUS when every reviewer must agree before proceeding –
 * a single rejection blocks the pipeline (e.g. security gates,
 * compliance checks, release sign-offs).
 */
fun workflow() = workflow("unanimous-test") {
    description = "Unanimous consensus: all auditors must approve"

    agents {
        agent("author") {
            model = Models.GEMINI_3_1_FLASH_LITE
            role = "Technical writer drafting a security policy"
        }
        agent("auditor1") {
            model = Models.GEMINI_3_1_FLASH_LITE
            role = "Security auditor focusing on access control"
        }
        agent("auditor2") {
            model = Models.GEMINI_3_1_FLASH_LITE
            role = "Security auditor focusing on data encryption"
        }
        agent("auditor3") {
            model = Models.GEMINI_3_1_FLASH_LITE
            role = "Security auditor focusing on compliance"
        }
        agent("compiler") {
            model = Models.GEMINI_3_1_PRO
            role = "Report compiler who synthesizes audit findings into a final report"
        }
    }

    state {
        variable("policy_draft", VarType.STRING, "the security policy draft to audit")
        variable("access_findings", VarType.STRING, "access control audit findings")
        variable("encryption_findings", VarType.STRING, "data encryption audit findings")
        variable("compliance_findings", VarType.STRING, "compliance audit findings")
        variable("final_report", VarType.STRING, "compiled security audit report")
    }

    graph {
        start at "draft-policy"

        node("draft-policy") {
            agent = "author"
            prompt = "Write a short security policy (2-3 sentences) about API key management for a cloud SaaS product."
            writes("policy_draft")
            onSuccess goto "audit"
        }

        parallel("audit") {
            branch("access_review") {
                agent = "auditor1"
                prompt = """
                    Audit the following security policy for access control gaps:
                    {policy_draft}

                    Identify any missing access control requirements.
                """.trimIndent()
                yields("access_findings")
            }

            branch("encryption_review") {
                agent = "auditor2"
                prompt = """
                    Audit the following security policy for encryption gaps:
                    {policy_draft}

                    Identify any missing encryption requirements.
                """.trimIndent()
                yields("encryption_findings")
            }

            branch("compliance_review") {
                agent = "auditor3"
                prompt = """
                    Audit the following security policy for compliance gaps:
                    {policy_draft}

                    Check against SOC2 and GDPR requirements.
                """.trimIndent()
                yields("compliance_findings")
            }

            consensus {
                strategy = ConsensusStrategy.UNANIMOUS
            }

            onConsensus goto "compile-report"
            onNoConsensus goto "blocked"
        }

        node("compile-report") {
            agent = "compiler"
            prompt = """
                Compile a brief security audit report from the following findings:

                Policy: {policy_draft}

                Access control findings: {access_findings}
                Encryption findings: {encryption_findings}
                Compliance findings: {compliance_findings}

                Produce a concise summary with all findings addressed.
            """.trimIndent()
            writes("final_report")
            onSuccess goto "approved"
        }

        end("approved", ExitStatus.SUCCESS)
        end("blocked", ExitStatus.FAILURE)
    }
}
