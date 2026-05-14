import io.quarkus.gradle.tasks.QuarkusDev

plugins {
    id("io.quarkus") version "3.35.2"
}

// Quarkus dev mode forks its own JVM — pass Java 24+ args via QuarkusDev task API
tasks.named<QuarkusDev>("quarkusDev") {
    openJavaLang.set(true)
    jvmArguments.add("--enable-native-access=ALL-UNNAMED")
    jvmArguments.add("--enable-preview")
}

// Exclude the JDK HTTP client — Quarkus provides JaxRsHttpClientBuilderFactory instead.
// Both implement the same SPI; having two causes a ServiceLoader conflict at runtime.
configurations.all {
    exclude(group = "dev.langchain4j", module = "langchain4j-http-client-jdk")
}

dependencies {
    // Quarkus BOMs
    implementation(platform("io.quarkus.platform:quarkus-bom:3.35.2"))
    implementation(platform("io.quarkus.platform:quarkus-langchain4j-bom:3.35.2"))

    // Internal Modules
    implementation(project(":hensu-core"))
    implementation(project(":hensu-serialization"))
    implementation(project(":hensu-langchain4j-adapter"))

    // Quarkus LangChain4j extensions — register GraalVM reflection metadata for native image
    implementation("io.quarkiverse.langchain4j:quarkus-langchain4j-anthropic")
    implementation("io.quarkiverse.langchain4j:quarkus-langchain4j-openai")
    implementation("io.quarkiverse.langchain4j:quarkus-langchain4j-ai-gemini")

    // Quarkus REST (reactive by default in Quarkus 3.x, includes SSE support)
    implementation("io.quarkus:quarkus-rest")
    implementation("io.quarkus:quarkus-rest-jackson")

    // Bean Validation — annotation-driven input validation for REST endpoints
    implementation("io.quarkus:quarkus-hibernate-validator")

    // Persistence — plain JDBC + Flyway (no ORM, virtual threads handle concurrency)
    implementation("io.quarkus:quarkus-jdbc-postgresql")
    implementation("io.quarkus:quarkus-flyway")

    // Scheduler — distributed recovery heartbeat and sweeper jobs
    implementation("io.quarkus:quarkus-scheduler")

    // Security — JWT bearer token authentication
    implementation("io.quarkus:quarkus-smallrye-jwt")

    // Testing
    val testcontainersVersion = "1.21.4"

    testImplementation("io.quarkus:quarkus-junit5")
    testImplementation("io.rest-assured:rest-assured")
    testImplementation("org.testcontainers:postgresql:${testcontainersVersion}")
    testImplementation("org.testcontainers:junit-jupiter:${testcontainersVersion}")
    testImplementation("org.flywaydb:flyway-database-postgresql")
    testImplementation("io.quarkus:quarkus-test-security-jwt")
}
