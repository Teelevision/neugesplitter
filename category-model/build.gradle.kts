plugins {
    alias(libs.plugins.kotlin.jvm)
    application
}

kotlin { jvmToolchain(17) }

dependencies { implementation(project(":domain")) }

// Trains the category guess (EN-13); `make categories` downloads the vectors and runs it.
application { mainClass = "eu.teele.neugesplitter.categories.TrainKt" }

tasks.named<JavaExec>("run") {
    workingDir = rootDir
    maxHeapSize = "2g"
}
