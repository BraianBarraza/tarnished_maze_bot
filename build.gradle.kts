plugins {
    `java-platform`
    alias(libs.plugins.kotlin.jvm) apply false
    alias(libs.plugins.jetbrains.dokka)
}

subprojects {
    plugins.withId("org.jetbrains.kotlin.jvm") {
        the<org.jetbrains.kotlin.gradle.dsl.KotlinJvmProjectExtension>().jvmToolchain(21)
    }
}

javaPlatform { allowDependencies() }

dependencies {
    api(platform("org.jetbrains.kotlin:kotlin-bom:${libs.versions.kotlin.get()}"))
//      implementation(libs.maze.ui)
    constraints {
        api("org.jetbrains.kotlin:kotlin-stdlib")
        api("org.jetbrains.kotlin:kotlin-stdlib-jdk8")

        api(libs.junit.jupiter)
    }

//    testImplementation(kotlin("test"))
}


