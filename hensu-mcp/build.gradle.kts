plugins {
    `java-library`
    `java-test-fixtures`
}

dependencies {
    api(project(":hensu-core"))
    api(platform("com.fasterxml.jackson:jackson-bom:2.20.1"))
    api("com.fasterxml.jackson.core:jackson-databind")
}
