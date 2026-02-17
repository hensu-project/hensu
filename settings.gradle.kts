pluginManagement {
    repositories {
        mavenCentral()
        gradlePluginPortal()
    }
}

rootProject.name = "hensu"
include("hensu-core")
include("hensu-cli")
include("hensu-langchain4j-adapter")