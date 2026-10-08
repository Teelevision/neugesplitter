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
        // Android 13 ships SQLite 3.32, and nothing newer may be assumed (TR-2, TR-16).
        val dialect = "app.cash.sqldelight:sqlite-3-30-dialect:${libs.versions.sqldelight.get()}"
        create("Database") {
            packageName.set("eu.teele.neugesplitter.storage")
            srcDirs("src/main/sqldelight")
            dialect(dialect)
        }
        // A file of its own, so that backups can leave it out (TR-37).
        create("SecretsDatabase") {
            packageName.set("eu.teele.neugesplitter.storage.settings")
            srcDirs("src/main/secrets")
            dialect(dialect)
        }
    }
}

dependencies {
    api(project(":domain"))
    api(project(":sync"))
    api(libs.kotlinx.coroutines.core)
    implementation(project(":format"))
    implementation(libs.sqldelight.android.driver)
    implementation(libs.sqldelight.coroutines)

    testImplementation(testFixtures(project(":domain")))
    testImplementation(testFixtures(project(":sync")))
    testImplementation(libs.sqldelight.sqlite.driver)
    testImplementation(libs.junit)
    testImplementation(libs.kotlin.test.junit)
}
