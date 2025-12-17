plugins {
    `java-library`
}

dependencies {
    implementation(platform(project(":")))

    implementation(libs.maze.common)
    implementation(libs.maze.client)

    compileOnly(libs.lombok)
    annotationProcessor(libs.lombok)

    testCompileOnly(libs.lombok)
    testAnnotationProcessor(libs.lombok)

    testImplementation(libs.junit.jupiter)
}

tasks.test {
    useJUnitPlatform()
}

java {
    toolchain { languageVersion.set(JavaLanguageVersion.of(21)) }
}