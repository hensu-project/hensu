import io.quarkus.gradle.tasks.QuarkusDev

plugins {
    id("io.quarkus") version "3.30.8"
    kotlin("jvm")
}

// Quarkus dev mode forks its own JVM — pass Java 24+ args via QuarkusDev task API
tasks.named<QuarkusDev>("quarkusDev") {
    openJavaLang.set(true)
    jvmArguments.add("--enable-native-access=ALL-UNNAMED")
}

dependencies {
    implementation(platform("io.quarkus.platform:quarkus-bom:3.30.8"))

    implementation(project(":hensu-core"))
    implementation(project(":hensu-serialization"))
    implementation(project(":hensu-langchain4j-adapter"))

    // Quarkus LangChain4j extensions — register GraalVM reflection metadata for native image
    implementation(platform("io.quarkus.platform:quarkus-langchain4j-bom:3.30.8"))
    implementation("io.quarkiverse.langchain4j:quarkus-langchain4j-anthropic")
    implementation("io.quarkiverse.langchain4j:quarkus-langchain4j-openai")
    implementation("io.quarkiverse.langchain4j:quarkus-langchain4j-ai-gemini")

    // Quarkus REST (reactive by default in Quarkus 3.x, includes SSE support)
    implementation("io.quarkus:quarkus-rest")
    implementation("io.quarkus:quarkus-rest-jackson")

    // Vert.x HTTP (included transitively, explicit for HTTP/2 and future HTTP/3)
    // HTTP/3 support is experimental in Quarkus - requires SSL configuration
    // See: https://quarkus.io/guides/http-reference#http3-experimental

    // Quarkus Scheduler for background tasks
    implementation("io.quarkus:quarkus-scheduler")

    // Quarkus CDI
    implementation("io.quarkus:quarkus-arc")
    implementation("io.quarkus:quarkus-kotlin")

    // Persistence — plain JDBC + Flyway (no ORM, virtual threads handle concurrency)
    implementation("io.quarkus:quarkus-jdbc-postgresql")
    implementation("io.quarkus:quarkus-flyway")

    // Security — JWT bearer token authentication
    implementation("io.quarkus:quarkus-smallrye-jwt")

    // Testing
    testImplementation("io.quarkus:quarkus-junit5")
    testImplementation("io.rest-assured:rest-assured")
    testImplementation("org.testcontainers:postgresql")
    testImplementation("org.testcontainers:junit-jupiter")
    testImplementation("org.flywaydb:flyway-database-postgresql")
    testImplementation("io.quarkus:quarkus-test-security-jwt")
}
