plugins {
    kotlin("jvm") version "2.1.10"
    application
}

group = "nl.ncaj"
version = "0.0.1"

repositories {
    mavenCentral()
}

dependencies {
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.10.1")
    implementation("org.xerial:sqlite-jdbc:3.44.1.0")
}

application {
    mainClass = "nl.ncaj.ApplicationKt"
}