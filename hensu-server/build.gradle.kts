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
    implementation(project(":hensu-mcp"))
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

    // Readiness for load balancers: a node that cannot reach Postgres, or is draining,
    // reports not-ready so traffic moves to the nodes that can serve it
    implementation("io.quarkus:quarkus-smallrye-health")

    // Testing (the Quarkus BOM manages Testcontainers under its 2.x artifact names)
    testImplementation("io.quarkus:quarkus-junit5")
    testImplementation("io.rest-assured:rest-assured")
    testImplementation("org.testcontainers:testcontainers-postgresql")
    testImplementation("org.testcontainers:testcontainers-junit-jupiter")
    testImplementation("org.flywaydb:flyway-database-postgresql")
    testImplementation("io.quarkus:quarkus-test-security-jwt")
}

// ========== Multi-node fault-injection test ==========
//
// Drives two server containers, Postgres and Toxiproxy through randomized fault schedules
// and checks the recorded history against the invariants of
// docs/multi-node-execution-protocol.md. Test-only: nothing here reaches the server's
// runtime classpath, and the task is not part of `check`.
//
//   ./gradlew :hensu-server:multiNodeFaultTest
//       (builds this server first)
//   ./gradlew :hensu-server:multiNodeFaultTest -PmultiNodeFaultTest.serverDir=<quarkus-app>
//       (runs a prebuilt server)
val multiNodeFaultTest: SourceSet by sourceSets.creating

dependencies {
    // The source set shares no configuration with the server, so it imports the BOM itself
    "multiNodeFaultTestImplementation"(platform("io.quarkus.platform:quarkus-bom:3.35.2"))
    "multiNodeFaultTestImplementation"("org.junit.jupiter:junit-jupiter")
    "multiNodeFaultTestImplementation"("org.assertj:assertj-core:3.27.7")
    "multiNodeFaultTestImplementation"("org.testcontainers:testcontainers")
    "multiNodeFaultTestImplementation"("org.testcontainers:testcontainers-postgresql")
    "multiNodeFaultTestImplementation"("org.testcontainers:testcontainers-toxiproxy")
    "multiNodeFaultTestImplementation"("org.postgresql:postgresql")
    "multiNodeFaultTestImplementation"("com.fasterxml.jackson.core:jackson-databind")
    "multiNodeFaultTestRuntimeOnly"("org.junit.platform:junit-platform-launcher")
    "multiNodeFaultTestRuntimeOnly"("org.slf4j:slf4j-simple:2.0.16")
}

tasks.register<Test>("multiNodeFaultTest") {
    description = "Runs the multi-node fault-injection test against a built server."
    group = "verification"
    testClassesDirs = multiNodeFaultTest.output.classesDirs
    classpath = multiNodeFaultTest.runtimeClasspath
    useJUnitPlatform()
    jvmArgs("--enable-preview", "--enable-native-access=ALL-UNNAMED")
    maxHeapSize = "2g"
    outputs.upToDateWhen { false }
    testLogging {
        events("passed", "failed", "skipped")
        showStandardStreams = true
        exceptionFormat = org.gradle.api.tasks.testing.logging.TestExceptionFormat.FULL
    }
    val externalServer = providers.gradleProperty("multiNodeFaultTest.serverDir")
    if (!externalServer.isPresent) {
        // Without a server directory the test runs against this module's own build, which
        // must therefore be current: a stale quarkus-app silently tests old code.
        dependsOn("quarkusBuild")
    }
    val serverDir =
        externalServer.orElse(layout.buildDirectory.dir("quarkus-app").map { it.asFile.absolutePath })
    systemProperty("multiNodeFaultTest.serverDir", serverDir.get())
    systemProperty(
        "multiNodeFaultTest.reportDir",
        layout.buildDirectory.dir("multi-node-fault-test").get().asFile.absolutePath,
    )
    listOf("seeds", "seconds", "expect", "skew").forEach { key ->
        providers.gradleProperty("multiNodeFaultTest.$key").orNull?.let {
            systemProperty("multiNodeFaultTest.$key", it)
        }
    }
}
