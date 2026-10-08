plugins { alias(libs.plugins.kotlin.jvm) }

kotlin { jvmToolchain(17) }

dependencies {
    api(project(":sync"))
    implementation(libs.okhttp)
    implementation(libs.kotlinx.coroutines.core)
}

testing {
    suites {
        val test by
            getting(JvmTestSuite::class) {
                useJUnit(libs.versions.junit)
                dependencies {
                    implementation(libs.kotlin.test.junit)
                    implementation(libs.kotlinx.coroutines.core)
                    implementation(libs.okhttp.mockwebserver)
                }
            }

        // Against a real S3 in a container (TR-48), so Docker is needed and `check` leaves it out.
        register<JvmTestSuite>("integrationTest") {
            useJUnit(libs.versions.junit)
            dependencies {
                implementation(project())
                implementation(testFixtures(project(":sync")))
                implementation(testFixtures(project(":domain")))
                implementation(libs.kotlin.test.junit)
                implementation(libs.kotlinx.coroutines.core)
                implementation(libs.testcontainers)
            }
        }
    }
}

// The suite builds stores with a page size of its own, which is internal.
kotlin.target.compilations.named("integrationTest") {
    associateWith(kotlin.target.compilations.getByName("main"))
}
