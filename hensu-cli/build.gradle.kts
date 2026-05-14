plugins {
    id("io.quarkus") version "3.35.2"
    kotlin("jvm")
}

configurations.all {
    exclude(group = "dev.langchain4j", module = "langchain4j-http-client-jdk")
}

dependencies {
    implementation(platform("io.quarkus.platform:quarkus-bom:3.35.2"))

    implementation(project(":hensu-dsl"))
    implementation(project(":hensu-serialization"))
    implementation(project(":hensu-langchain4j-adapter"))

    // Quarkus LangChain4j extensions — register GraalVM reflection metadata for native image
    implementation(platform("io.quarkus.platform:quarkus-langchain4j-bom:3.35.2"))
    implementation("io.quarkiverse.langchain4j:quarkus-langchain4j-anthropic")
    implementation("io.quarkiverse.langchain4j:quarkus-langchain4j-openai")
    implementation("io.quarkiverse.langchain4j:quarkus-langchain4j-ai-gemini")

    implementation("io.quarkus:quarkus-picocli")
    implementation("io.quarkus:quarkus-arc")
    implementation("io.quarkus:quarkus-kotlin")

    val kotlinVersion = "2.3.0"
    runtimeOnly("org.jetbrains.kotlin:kotlin-scripting-common:$kotlinVersion")
    runtimeOnly("org.jetbrains.kotlin:kotlin-scripting-jvm:$kotlinVersion")
    runtimeOnly("org.jetbrains.kotlin:kotlin-scripting-jvm-host:$kotlinVersion")
    runtimeOnly("org.jetbrains.kotlin:kotlin-script-runtime:$kotlinVersion")
    runtimeOnly("org.jetbrains.kotlin:kotlin-compiler-embeddable:$kotlinVersion")

    testImplementation("io.quarkus:quarkus-junit5")
    testImplementation("org.mockito:mockito-junit-jupiter:5.15.2")
}
