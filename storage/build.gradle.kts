plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.sqldelight)
}

kotlin { jvmToolchain(17) }

android {
    namespace = "eu.teele.neugesplitter.storage"
    compileSdk = 37

    defaultConfig { minSdk = 33 }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

sqldelight {
    databases {
        create("Database") {
            packageName.set("eu.teele.neugesplitter.storage")
            // Android 13 ships SQLite 3.32, and nothing newer may be assumed (TR-2, TR-16).
            dialect("app.cash.sqldelight:sqlite-3-30-dialect:${libs.versions.sqldelight.get()}")
        }
    }
}

dependencies {
    api(project(":domain"))
    api(libs.kotlinx.coroutines.core)
    implementation(project(":format"))
    implementation(libs.sqldelight.android.driver)
    implementation(libs.sqldelight.coroutines)

    testImplementation(testFixtures(project(":domain")))
    testImplementation(libs.sqldelight.sqlite.driver)
    testImplementation(libs.junit)
    testImplementation(libs.kotlin.test.junit)
}
