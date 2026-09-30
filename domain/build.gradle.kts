plugins { alias(libs.plugins.kotlin.jvm) }

kotlin { jvmToolchain(17) }

dependencies {
    testImplementation(libs.junit)
    testImplementation(libs.kotlin.test.junit)
    testImplementation(libs.kotest.property)
    testImplementation(libs.kotlinx.coroutines.core)
}

tasks.test { useJUnit() }
