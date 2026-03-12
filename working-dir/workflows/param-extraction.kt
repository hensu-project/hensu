/**
 * Example: Parameter extraction between workflow steps
 *
 * Demonstrates how to extract specific values from one step
 * and use them as placeholders in subsequent prompts.
 */
fun paramExtractionWorkflow() = workflow("ParamExtraction") {
    description = "Demo of parameter extraction between steps"
    version = "1.0.0"

    state {
        variable("largest_lake",       VarType.STRING, "name of the largest lake in Georgia")
        variable("highest_peak",       VarType.STRING, "name and height of the highest mountain in Georgia")
        variable("capital_population", VarType.STRING, "population of Tbilisi")
        variable("oldest_wine_region", VarType.STRING, "name of the oldest wine-producing region")
        variable("advertisement",      VarType.STRING, "travel advertisement incorporating the extracted facts")
    }

    agents {
        agent("researcher") {
            role = "Research Assistant"
            model = Models.GEMINI_2_5_FLASH
            temperature = 0.3  // Lower temperature for more precise outputs
        }
    }

    graph {
        start at "extract_facts"

        // Step 1: Extract specific facts about Georgia
        // The prompt instructs the agent to output in a specific JSON format
        node("extract_facts") {
            agent = "researcher"
            prompt = """
                Research Georgia (the country) and provide the following specific facts.

                IMPORTANT: Output your response as JSON with exactly these keys:
                {
                    "largest_lake": "<name of the largest lake>",
                    "highest_peak": "<name and height of highest mountain>",
                    "capital_population": "<population of Tbilisi>",
                    "oldest_wine_region": "<name of the wine region>"
                }

                Only output the JSON, nothing else.
            """.trimIndent()

            writes("largest_lake", "highest_peak", "capital_population", "oldest_wine_region")

            onSuccess goto "use_facts"
        }

        // Step 2: Use the extracted parameters in a new prompt
        node("use_facts") {
            agent = "researcher"
            prompt = """
                Using these verified facts about Georgia:
                - Largest lake: {largest_lake}
                - Highest peak: {highest_peak}
                - Capital population: {capital_population}
                - Wine region: {oldest_wine_region}

                Write a compelling 2-paragraph travel advertisement that incorporates
                ALL of these specific facts. Make it sound exciting and accurate.
            """.trimIndent()
            writes("advertisement")
            onSuccess goto "end"
        }

        end("end")
    }
}
