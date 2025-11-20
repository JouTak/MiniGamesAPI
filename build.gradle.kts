import java.util.UUID
val group: String by project
val version: String by project
val repo: String by project

project.group = group
project.version = version


plugins {
    alias(libs.plugins.kotlinJvm)
    alias(libs.plugins.shadow)
    kotlin("plugin.serialization") version libs.versions.kotlin.get()
    `maven-publish`
}

repositories {
    mavenCentral()
    maven("https://repo.papermc.io/repository/maven-public/")
    maven("https://oss.sonatype.org/content/groups/public/")
    maven("https://repo.kyngs.xyz/public")
}

dependencies {
    compileOnly(libs.kotlin)
    compileOnly(libs.paper)
    compileOnly(libs.librelogin)
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.6.2")
}

kotlin {
    jvmToolchain(libs.versions.jdk.get().toInt())
}

tasks.build {
    dependsOn(tasks.shadowJar)
}

tasks.jar {
    enabled = false
}

tasks.processResources {
    val minecraftVersion = libs.versions.paper.get().substringBefore("-")
    val commitHash = project.findProperty("commitHash") as String?

    val website = if (repo.isBlank()) "https://joutak.ru"
    else commitHash?.let { "$repo/tree/$it" } ?: repo

    val props = mapOf(
        "NAME" to project.name,
        "VERSION" to project.version,
        "MINECRAFT_VERSION" to minecraftVersion,
        "KOTLIN_VERSION" to libs.versions.kotlin.get(),
        "WEBSITE" to website,
    )

    inputs.properties(props)
    filteringCharset = "UTF-8"
    filesMatching("plugin.yml") { expand(props) }
}

tasks.shadowJar {
    val randomSuffix = UUID.randomUUID().toString().substring(0, 8)
    archiveFileName.set("${project.name}-${project.version}-${randomSuffix}.jar")

    // we do NOT want him here
    dependencies {
        exclude(dependency("org.jetbrains.kotlin:.*"))
        exclude(dependency("org.jetbrains:annotations"))
    }

    if (System.getenv("TEST_PLUGIN_BUILD") != null) {
        val serverPath = System.getenv("SERVER_PATH")
        if (serverPath != null) {
            destinationDirectory.set(file("$serverPath/plugins"))
        } else {
            logger.warn("SERVER_PATH property is not set!")
        }
    }
}

publishing {
    publications {
        create<MavenPublication>("maven") {
            artifact(tasks.shadowJar)
            groupId = project.group.toString()
            artifactId = project.name.lowercase()
            version = project.version.toString()
        }
    }
    repositories {
        maven {
            name = "Reposilite"
            val repoUrl = "https://maven.joutak.ru"

            url = uri(if (version.toString().endsWith("SNAPSHOT")) "$repoUrl/snapshots" else "$repoUrl/releases")

            credentials {
                username = System.getenv("REPOSILITE_USER")
                password = System.getenv("REPOSILITE_TOKEN")
            }
            authentication {
                create<BasicAuthentication>("basic")
            }
        }
    }
}