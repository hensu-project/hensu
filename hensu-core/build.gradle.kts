plugins {
    `java-library`
}

dependencies {
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
