group = "com.jaju.spotify-shuffler"
version = "0.0.1"

plugins {
    kotlin("jvm") version "1.8.20"
    application
    id("com.github.johnrengelman.shadow") version "7.1.2"
}

java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}

application {
    mainClass.set("com.jaju.spotify.shuffler.ApplicationKt")
}

tasks {
    shadowJar {
        archiveBaseName.set("spotify-shuffler")
        archiveClassifier.set("")
        archiveVersion.set("")
    }

    compileKotlin {
        kotlinOptions {
            allWarningsAsErrors
        }
    }

    //Local test
    runShadow {
        doFirst {
            environment["SPOTIFY_BOT_TOKEN"] = "_"
            environment["SPOTIFY_CLIENT_ID"] = "_"
            environment["SPOTIFY_CLIENT_SECRET"] = "_"
            environment["SPOTIFY_REDIRECT_URL"] = "http://localhost:8888/callback"
            environment["SPOTIFY_ALLOWED_USERS"] = "_"
        }
    }
}

repositories {
    mavenCentral()
    maven("https://jitpack.io")
    maven("https://oss.sonatype.org/content/repositories/snapshots")
}

dependencies {
    implementation(kotlin("stdlib"))
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.7.3")

    implementation("io.arrow-kt:arrow-core:1.2.0")

    implementation("com.adamratzman:spotify-api-kotlin-core:4.0.2")

    implementation("io.github.kotlin-telegram-bot.kotlin-telegram-bot:telegram:6.1.0")

    implementation(platform("org.http4k:http4k-bom:5.4.1.0"))
    implementation("org.http4k:http4k-core")
    implementation("org.http4k:http4k-server-undertow")
}