plugins {
    id("io.quarkus") version "3.30.8"
    kotlin("jvm")
}

dependencies {
    implementation(platform("io.quarkus.platform:quarkus-bom:3.30.8"))

    implementation(project(":hensu-core"))
    implementation(project(":hensu-langchain4j-adapter"))

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

    // Persistence (optional, can be enabled when needed)
    // implementation("io.quarkus:quarkus-hibernate-orm-panache")
    // implementation("io.quarkus:quarkus-jdbc-postgresql")

    // Testing
    testImplementation("io.quarkus:quarkus-junit5")
    testImplementation("io.rest-assured:rest-assured")
    testImplementation("org.mockito:mockito-junit-jupiter:5.15.2")
    testImplementation("org.assertj:assertj-core:3.27.3")
}
