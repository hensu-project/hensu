plugins {
    id("org.springframework.boot") version "3.4.2"
    id("io.spring.dependency-management") version "1.1.7"
    java
}

group = "io.hensu"
version = "1.0.0-SNAPSHOT"

java {
    toolchain {
        languageVersion = JavaLanguageVersion.of(21)
    }
}

springBoot {
    mainClass.set("io.hensu.integrations.springclient.HensuReferenceApp")
}

repositories {
    mavenCentral()
}

dependencies {
    // Servlet stack for REST endpoints (ReviewController, DemoRunner)
    implementation("org.springframework.boot:spring-boot-starter-web")

    // WebFlux WebClient for reactive SSE consumption (HensuEventStream, HensuMcpTransport)
    // Co-exists with the servlet stack: Tomcat serves HTTP, WebClient handles outbound SSE.
    implementation("org.springframework.boot:spring-boot-starter-webflux")

    implementation("com.fasterxml.jackson.core:jackson-databind")

    testImplementation("org.springframework.boot:spring-boot-starter-test")
}
