plugins {
    `java-library`
    kotlin("jvm")
}

val kotlinVersion: String = "2.3.0"

dependencies {
    // Kotlin stdlib
    implementation(kotlin("stdlib"))
    implementation(kotlin("reflect"))

    // Kotlin Scripting
    implementation("org.jetbrains.kotlin:kotlin-scripting-common:$kotlinVersion")
    implementation("org.jetbrains.kotlin:kotlin-scripting-jvm:$kotlinVersion")
    implementation("org.jetbrains.kotlin:kotlin-scripting-jvm-host:$kotlinVersion")
    implementation("org.jetbrains.kotlin:kotlin-scripting-dependencies:$kotlinVersion")
    implementation("org.jetbrains.kotlin:kotlin-script-runtime:$kotlinVersion")

    // JSR-223 scripting support (alternative for restricted classloader environments)
    implementation("org.jetbrains.kotlin:kotlin-scripting-jsr223:$kotlinVersion")

    // Compiler embeddable for script compilation
    implementation("org.jetbrains.kotlin:kotlin-compiler-embeddable:$kotlinVersion")

    // Additional test dependencies (base deps inherited from root)
    testImplementation("org.mockito:mockito-junit-jupiter:5.15.2")
    testImplementation("net.bytebuddy:byte-buddy:1.17.5")
    testImplementation("net.bytebuddy:byte-buddy-agent:1.17.5")
}

// Ensure no dependencies leak in io.hensu.core
configurations.api {
    dependencies.all {
        if (this.group != "io.hensu.core") {
            throw GradleException(
                "hensu-core must have ZERO external dependencies! Found: $this"
            )
        }
    }
}

// Task to run a workflow file directly
tasks.register<JavaExec>("runWorkflow") {
    jvmArgs("--enable-native-access=ALL-UNNAMED")

    group = "application"
    description = "Run a workflow file directly"
    mainClass.set("io.hensu.dsl.runners.SimpleRunnerKt")
    classpath = sourceSets["main"].runtimeClasspath
    workingDir = rootProject.projectDir

    // Pass command line arguments via -Pworkflow=path/to/workflow.kt -Pargs="--stub --verbose"
    val workflowFile = project.findProperty("workflow")?.toString() ?: "working-dir/workflows/georgia-discovery.kt"
    val extraArgs = project.findProperty("args")?.toString()?.split(" ") ?: emptyList()
    args = listOf(workflowFile) + extraArgs
}
