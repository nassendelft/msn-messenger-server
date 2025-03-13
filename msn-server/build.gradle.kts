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
}

application {
    mainClass = "nl.ncaj.ApplicationKt"
}