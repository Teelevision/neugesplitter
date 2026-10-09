import com.android.build.api.variant.HostTestBuilder
import org.gradle.api.artifacts.component.ModuleComponentIdentifier
import org.gradle.api.artifacts.result.ResolvedComponentResult
import org.gradle.api.artifacts.result.ResolvedDependencyResult

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.compose.compiler)
    alias(libs.plugins.kotlin.serialization)
}

kotlin { jvmToolchain(17) }

// Robolectric runs SDK 35 and later only on Java 21; the code itself still targets 17.
tasks.withType<Test>().configureEach {
    javaLauncher = javaToolchains.launcherFor { languageVersion = JavaLanguageVersion.of(21) }
    jvmArgs("--add-exports=java.base/jdk.internal.access=ALL-UNNAMED")
}

android {
    namespace = "eu.teele.neugesplitter"
    compileSdk = 37

    defaultConfig {
        applicationId = "eu.teele.neugesplitter"
        minSdk = 33
        targetSdk = 37
        versionCode = 1
        versionName = "1.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        vectorDrawables { useSupportLibrary = true }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    buildFeatures {
        compose = true
        resValues = true
    }
    packaging { resources { excludes += "/META-INF/{AL2.0,LGPL2.1}" } }
    testOptions { unitTests { isIncludeAndroidResources = true } }
}

dependencies {
    implementation(project(":storage"))
    implementation(project(":format"))
    implementation(project(":backend-s3"))
    implementation(libs.sqldelight.android.driver)
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.lifecycle.viewmodel.navigation3)
    implementation(libs.androidx.navigation3.runtime)
    implementation(libs.androidx.navigation3.ui)
    implementation(libs.androidx.activity.compose)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.ui)
    implementation(libs.androidx.ui.graphics)
    implementation(libs.androidx.ui.tooling.preview)
    implementation(libs.androidx.material3)
    implementation(libs.androidx.work.runtime)

    testImplementation(testFixtures(project(":sync")))
    testImplementation(libs.junit)
    testImplementation(libs.kotlin.test.junit)
    testImplementation(libs.robolectric)
    testImplementation(libs.androidx.work.testing)
    testImplementation(platform(libs.androidx.compose.bom))
    testImplementation(libs.androidx.ui.test.junit4)
    // ui-test-junit4 brings Espresso 3.5, which fails on SDK 37 (InputManager.getInstance).
    testImplementation(libs.androidx.espresso.core)
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(platform(libs.androidx.compose.bom))
    androidTestImplementation(libs.androidx.ui.test.junit4)
    debugImplementation(libs.androidx.ui.tooling)
    debugImplementation(libs.androidx.ui.test.manifest)
}

/** Writes the release build's external `group:module`s, one per line. */
abstract class ModuleList : DefaultTask() {
    @get:Input abstract val modules: SetProperty<String>
    @get:OutputDirectory abstract val output: DirectoryProperty

    @TaskAction
    fun write() {
        output
            .file("release-modules.txt")
            .get()
            .asFile
            .writeText(modules.get().sorted().joinToString("\n"))
    }
}

fun modules(root: ResolvedComponentResult): Set<String> {
    val seen = mutableSetOf<ResolvedComponentResult>()
    val queue = ArrayDeque(listOf(root))
    while (queue.isNotEmpty()) {
        val component = queue.removeFirst()
        if (seen.add(component)) {
            component.dependencies.filterIsInstance<ResolvedDependencyResult>().forEach {
                queue += it.selected
            }
        }
    }
    return seen
        .mapNotNull {
            (it.id as? ModuleComponentIdentifier)?.let { id -> "${id.group}:${id.module}" }
        }
        .toSet()
}

// The licences screen's test holds its list against these.
val releaseModules =
    tasks.register<ModuleList>("releaseModules") {
        modules =
            configurations
                .named("releaseRuntimeClasspath")
                .flatMap { it.incoming.resolutionResult.rootComponent }
                .map(::modules)
        output = layout.buildDirectory.dir("generated/releaseModules")
    }

androidComponents.onVariants { variant ->
    variant.hostTests[HostTestBuilder.UNIT_TEST_TYPE]
        ?.sources
        ?.resources
        ?.addGeneratedSourceDirectory(releaseModules, ModuleList::output)
}
