import io.quarkus.gradle.tasks.QuarkusDev

plugins {
    id("io.quarkus") version "3.30.8"
}

// Quarkus dev mode forks its own JVM — pass Java 24+ args via QuarkusDev task API
tasks.named<QuarkusDev>("quarkusDev") {
    openJavaLang.set(true)
    jvmArguments.add("--enable-native-access=ALL-UNNAMED")
}

dependencies {
    // Quarkus BOMs
    implementation(platform("io.quarkus.platform:quarkus-bom:3.30.8"))
    implementation(platform("io.quarkus.platform:quarkus-langchain4j-bom:3.30.8"))

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
