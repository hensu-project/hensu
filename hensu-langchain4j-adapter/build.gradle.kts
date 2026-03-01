plugins {
    `java-library`
}

dependencies {
    // Version-managed by Quarkus Platform LangChain4j BOM (aligned with Quarkus 3.30.8)
    api(platform("io.quarkus.platform:quarkus-langchain4j-bom:3.30.8"))

    api(project(":hensu-core"))

    implementation("dev.langchain4j:langchain4j")
    implementation("dev.langchain4j:langchain4j-anthropic")
    implementation("dev.langchain4j:langchain4j-open-ai")
    implementation("dev.langchain4j:langchain4j-google-ai-gemini")
    // JDK HTTP client — required for programmatic ChatModel instantiation outside CDI.
    // HttpClientBuilderLoader uses ServiceLoader; native image only registers services it sees at build time.
    implementation("dev.langchain4j:langchain4j-http-client-jdk")
}
