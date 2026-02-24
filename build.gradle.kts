import com.diffplug.gradle.spotless.SpotlessExtension

plugins {
    java
    kotlin("jvm") version "2.3.0" apply false
    id("com.diffplug.spotless") version "6.25.0" apply false
}

allprojects {
    group = "io.hensu"
    version = "0.1.0-SNAPSHOT"

    repositories {
        mavenCentral()
    }
}

subprojects {
    apply(plugin = "java")
    apply(plugin = "com.diffplug.spotless")

    java {
        toolchain {
            languageVersion.set(JavaLanguageVersion.of(25))
        }
    }

    dependencies {
        // Test dependencies - shared across all modules
        "testImplementation"("org.junit.jupiter:junit-jupiter:5.11.4")
        "testImplementation"("org.assertj:assertj-core:3.27.7")
        "testImplementation"("org.mockito:mockito-core:5.15.2")
        "testRuntimeOnly"("org.junit.platform:junit-platform-launcher")
    }

    val jvm25Args = listOf(
        "--enable-native-access=ALL-UNNAMED",
        "--add-opens", "java.base/java.lang=ALL-UNNAMED"
    )

    tasks.withType<JavaCompile> {
        options.encoding = "UTF-8"
        options.compilerArgs.add("-parameters")
    }

    tasks.withType<JavaExec> {
        jvmArgs(jvm25Args)
    }

    tasks.test {
        jvmArgs(jvm25Args)
        useJUnitPlatform()
    }

    configure<SpotlessExtension> {
        java {
            target("src/*/java/**/*.java")
            googleJavaFormat("1.28.0")
                .groupArtifact("com.google.googlejavaformat:google-java-format")
                .aosp()
        }

        kotlin {
            target("src/*/kotlin/**/*.kt")
            ktfmt("0.53").kotlinlangStyle()
        }
    }
}
