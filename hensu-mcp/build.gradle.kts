plugins {
    `java-library`
    `java-test-fixtures`
}

dependencies {
    api(project(":hensu-core"))
    api(platform("com.fasterxml.jackson:jackson-bom:2.20.1"))
    api("com.fasterxml.jackson.core:jackson-databind")
}

// ========== MCP revision coverage ==========
//
// Runs list, call and cancel against real MCP servers pinned to each protocol revision
// Hensu has to reach – the reference `everything` server on 2024-11-05, 2025-06-18,
// 2025-11-25 and 2026-07-28, and a Spring AI MCP server – each built into a container
// image. It needs Docker and the network to build those images, so it is not part of
// `check`; CI runs it as a job of its own. The servers' Docker build contexts live in
// `src/mcpRevisionTest/docker/`, outside the source set, so they never reach the classpath.
//
//   ./gradlew :hensu-mcp:mcpRevisionTest
val mcpRevisionTest: SourceSet by sourceSets.creating {
    compileClasspath += sourceSets.main.get().output
    runtimeClasspath += sourceSets.main.get().output
}

configurations[mcpRevisionTest.implementationConfigurationName].extendsFrom(configurations.api.get())

dependencies {
    // Testcontainers versions follow the Quarkus BOM, as in the server's fault test, so
    // the repository resolves one Testcontainers line
    "mcpRevisionTestImplementation"(platform("io.quarkus.platform:quarkus-bom:3.35.2"))
    "mcpRevisionTestImplementation"("org.junit.jupiter:junit-jupiter")
    "mcpRevisionTestImplementation"("org.assertj:assertj-core:3.27.7")
    "mcpRevisionTestImplementation"("org.testcontainers:testcontainers")
    "mcpRevisionTestImplementation"("org.testcontainers:testcontainers-junit-jupiter")
    "mcpRevisionTestRuntimeOnly"("org.junit.platform:junit-platform-launcher")
    "mcpRevisionTestRuntimeOnly"("org.slf4j:slf4j-simple:2.0.16")
}

tasks.register<Test>("mcpRevisionTest") {
    description = "Runs list, call and cancel against MCP servers pinned to each revision."
    group = "verification"
    testClassesDirs = mcpRevisionTest.output.classesDirs
    classpath = mcpRevisionTest.runtimeClasspath
    useJUnitPlatform()
    jvmArgs("--enable-preview", "--enable-native-access=ALL-UNNAMED")
    systemProperty(
        "hensu.revision.contexts",
        layout.projectDirectory.dir("src/mcpRevisionTest/docker").asFile.absolutePath
    )
    outputs.upToDateWhen { false }
    testLogging {
        events("passed", "failed", "skipped")
        showStandardStreams = true
        exceptionFormat = org.gradle.api.tasks.testing.logging.TestExceptionFormat.FULL
    }
}
