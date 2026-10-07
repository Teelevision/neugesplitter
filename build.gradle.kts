// Top-level build file where you can add configuration options common to all subprojects/modules.
import java.io.File

plugins {
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.android.library) apply false
    alias(libs.plugins.kotlin.android) apply false
    alias(libs.plugins.kotlin.jvm) apply false
    alias(libs.plugins.compose.compiler) apply false
    alias(libs.plugins.kotlin.serialization) apply false
    alias(libs.plugins.sqldelight) apply false
    id("com.diffplug.spotless") version "8.10.2"
}

// Prettier needs npm, which a clean checkout or an F-Droid builder may not have (TR-51).
val npmLocation =
    System.getenv("PATH")
        ?.split(File.pathSeparator)
        ?.map { File(it, "npm") }
        ?.firstOrNull { it.canExecute() }

spotless {
    kotlin {
        target("**/*.kt")
        targetExclude("**/build/**")
        ktfmt("0.58").kotlinlangStyle()
    }
    kotlinGradle {
        target("**/*.gradle.kts")
        targetExclude("**/build/**")
        ktfmt("0.58").kotlinlangStyle()
    }
    format("markdown") {
        target("**/*.md")
        targetExclude("**/build/**", ".artifacts/**")
        trimTrailingWhitespace()
        endWithNewline()
        if (npmLocation != null) {
            prettier()
                .npmExecutable(npmLocation)
                .config(mapOf("printWidth" to 80, "proseWrap" to "always"))
        } else {
            logger.lifecycle("npm not found; skipping prettier for markdown.")
        }
    }
}
