plugins {
    kotlin("jvm")
    `java-library`
    alias(libs.plugins.jetbrains.dokka)
}

dependencies {
    implementation(platform(project(":")))

    implementation(libs.maze.common)
    implementation(libs.maze.client)
    implementation(libs.kotlinx.coroutines.core)

    testImplementation(kotlin("test"))
    testImplementation(libs.junit.jupiter)
}

tasks.test {
    useJUnitPlatform()
}

kotlin {
    jvmToolchain(21)
}