plugins {
    `java-library`
}

val langchain4jVersion = "1.0.0-alpha1"

dependencies {
    api(project(":hensu-core"))

    implementation("dev.langchain4j:langchain4j:${langchain4jVersion}")
    implementation("dev.langchain4j:langchain4j-anthropic:${langchain4jVersion}")
    implementation("dev.langchain4j:langchain4j-open-ai:${langchain4jVersion}")
    implementation("dev.langchain4j:langchain4j-google-ai-gemini:${langchain4jVersion}")
}