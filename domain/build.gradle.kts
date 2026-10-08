plugins {
    alias(libs.plugins.kotlin.jvm)
    `java-test-fixtures`
}

kotlin { jvmToolchain(17) }

dependencies {
    implementation(libs.kotlinx.collections.immutable)

    // The generators and the log builder, shared with the modules that encode what they build.
    testFixturesApi(libs.kotest.property)

    testImplementation(libs.junit)
    testImplementation(libs.kotlin.test.junit)
    testImplementation(libs.kotest.property)
    testImplementation(libs.kotlinx.coroutines.core)
}

tasks.test { useJUnit() }
