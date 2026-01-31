plugins {
    id("io.quarkus") version "3.30.8"
    kotlin("jvm")
}

dependencies {
    implementation(platform("io.quarkus.platform:quarkus-bom:3.30.8"))

    implementation(project(":hensu-core"))
    implementation(project(":hensu-langchain4j-adapter"))

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
