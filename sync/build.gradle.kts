plugins {
    alias(libs.plugins.kotlin.jvm)
    `java-test-fixtures`
}

kotlin { jvmToolchain(17) }

dependencies {
    api(project(":format"))
    api(libs.kotlinx.coroutines.core)
    // The in-memory local log, shared with the backend suite that writes through it (TR-48).
    testFixturesApi(project(":format"))
    testImplementation(testFixtures(project(":domain")))
    testImplementation(libs.junit)
    testImplementation(libs.kotlin.test.junit)
    testImplementation(libs.kotlinx.coroutines.test)
}

tasks.test { useJUnit() }
