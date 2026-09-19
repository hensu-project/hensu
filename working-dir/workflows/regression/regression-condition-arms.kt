/**
 * Regression: condition routing reads a variable the node itself wrote, and the else-arm catches
 * everything the explicit arms do not.
 *
 * `onCondition("tier")` declares `tier` as a routing variable, which the engine must wire into
 * extraction so that the value is present in state by the time the rule is evaluated. When that
 * wiring is missing the symptom is quiet: every run falls through to the else-arm, because the
 * variable reads as absent rather than as any declared value. Routing all three arms — one per
 * input profile — is what separates "the else-arm works" from "only the else-arm works".
 *
 * The classification is a stated rule over a number in the input, not a judgement call, so a live
 * agent lands on the same tier every time.
 *
 * Only gold and silver are routed by an explicit arm; anything else is unclassified, and that end
 * node carries FAILURE. The exit status therefore reports which kind of arm fired — an explicit
 * one or the else-arm — and `-v` shows the extracted value, so the two together identify the arm
 * exactly.
 *
 * Run each arm:
 *   hensu run regression/regression-condition-arms -d working-dir -v --no-daemon \
 *     -c '{"account": "A-1", "committed_spend": 4200000}'   → tier gold,   SUCCESS
 *   ... "committed_spend": 250000                            → tier silver, SUCCESS
 *   ... "committed_spend": 2000                              → tier bronze, FAILURE
 *
 * A gold or silver run that reports FAILURE has fallen through to the else-arm, which is the
 * regression: the routing variable never reached state.
 */
fun regressionConditionArms() = workflow("regression-condition-arms") {
    description = "Condition routing — routing variable wiring and else-arm coverage"
    version = "1.0.0"

    agents {
        agent("classifier") {
            role = "Account classifier. You apply the stated banding rule literally and never " +
                "substitute your own judgement. Return JSON with keys: tier and reason."
            model = Models.GEMINI_3_1_FLASH_LITE
            temperature = 0.0
        }
    }

    state {
        input("account", VarType.STRING)
        input("committed_spend", VarType.NUMBER)
        variable("tier", VarType.STRING, "classified account tier")
        variable("reason", VarType.STRING, "why the account was classified this way")
    }

    graph {
        start at "classify"

        node("classify") {
            agent = "classifier"
            prompt = """
                Classify account {account}, whose annual committed spend is {committed_spend}.

                Apply this banding exactly:
                  committed spend of 1000000 or more  → tier "gold"
                  committed spend from 100000 to 999999 → tier "silver"
                  anything below 100000               → tier "bronze"

                Report the tier as a lowercase word.
            """.trimIndent()
            writes("tier", "reason")

            onCondition("tier") {
                whenValue equalTo "gold" goto "premium"
                whenValue equalTo "silver" goto "standard"
                // Every remaining value, including a missing one — which is exactly what a
                // broken routing-variable wiring produces. FAILURE here is what makes the
                // fall-through visible from the exit status alone.
                otherwise goto "unclassified"
            }
        }

        end("premium", ExitStatus.SUCCESS)
        end("standard", ExitStatus.SUCCESS)
        end("unclassified", ExitStatus.FAILURE)
    }
}
