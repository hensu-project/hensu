pluginManagement {
    repositories {
        mavenCentral()
        gradlePluginPortal()
    }
}

rootProject.name = "hensu"
include("hensu-dsl")
include("hensu-core")
include("hensu-cli")
include("hensu-langchain4j-adapter")
include("hensu-serialization")
include("hensu-server")
