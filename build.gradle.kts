import org.jetbrains.kotlin.gradle.tasks.KotlinCompile

plugins {
    kotlin("jvm") version "1.5.0"
    application
}

group = "com.jaju.spotify-shuffler"
version = "1.0.0"

repositories {
    mavenCentral()
}

dependencies {
    implementation(kotlin("stdlib"))
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.6.0-RC")

    implementation("com.adamratzman:spotify-api-kotlin-core:3.8.4")

    implementation("org.http4k:http4k-core:4.17.0.0")
    implementation("org.http4k:http4k-server-undertow:4.17.0.0")

}

tasks.withType<KotlinCompile>() {
    kotlinOptions.jvmTarget = "11"
}

tasks.withType<Jar> {
    manifest {
        attributes["Main-Class"] = "com.jaju.spotify.shuffler.MainKt"
    }
    from(sourceSets.main.get().output)

    dependsOn(configurations.runtimeClasspath)
    from({
        configurations.runtimeClasspath.get().filter { it.name.endsWith("jar") }.map { zipTree(it) }
    })
}