plugins {
    id("net.fabricmc.fabric-loom") version "1.19.3"
    kotlin("jvm") version "2.4.21"
}

// gradle.properties uses snake_case keys, so read them by exact name.
fun prop(name: String): String = project.property(name) as String

version = prop("mod_version")
group = prop("maven_group")

base {
    archivesName.set(prop("archives_base_name"))
}

repositories {
    mavenCentral()
    maven("https://maven.fabricmc.net/")
    maven("https://maven.terraformersmc.com/releases/")
}

dependencies {
    "minecraft"("com.mojang:minecraft:${prop("minecraft_version")}")

    implementation("net.fabricmc:fabric-loader:${prop("loader_version")}")
    implementation("net.fabricmc.fabric-api:fabric-api:${prop("fabric_api_version")}")
    implementation("net.fabricmc:fabric-language-kotlin:${prop("fabric_kotlin_version")}")
    implementation("com.terraformersmc:modmenu:${prop("modmenu_version")}")
}

tasks.processResources {
    inputs.property("version", project.version)
    filesMatching("fabric.mod.json") {
        expand(mapOf("version" to project.version))
    }
}

tasks.withType<JavaCompile>().configureEach {
    options.release.set(25)
}

kotlin {
    jvmToolchain(25)
}

java {
    withSourcesJar()
}
