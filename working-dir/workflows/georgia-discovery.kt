fun georgiaDiscoveryWorkflow() = workflow("GeorgiaDiscovery") {
    description = "Discover the beautiful country of Georgia"
    version = "1.0.0"

    agents {
        agent("explorer") {
            role = "Travel and Culture Expert"
            model = Models.GEMINI_2_5_FLASH
            temperature = 0.8
        }
    }

    graph {
        start at "introduction"

        node("introduction") {
            agent = "explorer"
            prompt = """
                Provide a comprehensive summary of Georgia. 
                Focus on three areas: its nature (specifically its alpine topography and water features), 
                its unique culture, and its varied landscapes. 
                Ensure you highlight the role that mountain ranges and glacial lakes play in the country's geography. 
                Keep the response concise but informative.
            """.trimIndent()

            onSuccess goto "mountain"
        }

        node("mountain") {
            agent = "explorer"
            prompt = "../prompts/georgian-mountain.md"

            onSuccess goto "end"
        }

        end("end")
    }
}