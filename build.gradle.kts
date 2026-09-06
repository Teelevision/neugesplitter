// Top-level build file where you can add configuration options common to all subprojects/modules.
import java.io.File

plugins {
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.kotlin.android) apply false
    alias(libs.plugins.compose.compiler) apply false
    id("com.diffplug.spotless") version "8.10.2"
}

// Prettier needs npm, which a clean checkout or an F-Droid builder may not have (TR-51).
val npmLocation =
    System.getenv("PATH")
        ?.split(File.pathSeparator)
        ?.map { File(it, "npm") }
        ?.firstOrNull { it.canExecute() }

spotless {
    format("markdown") {
        target("**/*.md")
        targetExclude("**/build/**")
        trimTrailingWhitespace()
        endWithNewline()
        if (npmLocation != null) {
            prettier()
                .npmExecutable(npmLocation)
                .config(
                    mapOf(
                        "printWidth" to 80,
                        "proseWrap" to "always",
                    ),
                )
        } else {
            logger.lifecycle("npm not found; skipping prettier for markdown.")
        }
    }
}
