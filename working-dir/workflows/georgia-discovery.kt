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

    state {
        variable("overview",       VarType.STRING, "comprehensive overview of Georgia covering nature, culture, and landscapes")
        variable("mountain_name",  VarType.STRING, "the name of the most iconic Georgian mountain to explore")
        variable("mountain_guide", VarType.STRING, "detailed guide to the selected Georgian mountain")
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
                Also, name the single most iconic mountain in Georgia — it will be explored in detail next.
            """.trimIndent()
            writes("overview", "mountain_name")
            onSuccess goto "mountain"
        }

        node("mountain") {
            agent = "explorer"
            prompt = "georgian-mountain.md"
            writes("mountain_guide")
            onSuccess goto "end"
        }

        end("end")
    }
}
