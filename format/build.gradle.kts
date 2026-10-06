plugins { alias(libs.plugins.kotlin.jvm) }

kotlin { jvmToolchain(17) }

dependencies {
    api(project(":domain"))
    implementation(libs.kotlinx.serialization.json)

    testImplementation(testFixtures(project(":domain")))
    testImplementation(libs.junit)
    testImplementation(libs.kotlin.test.junit)
    testImplementation(libs.kotest.property)
}

tasks.test {
    useJUnit()

    // The vectors live beside the specification they belong to (format-spec 10).
    val docs = rootProject.layout.projectDirectory.dir("docs")
    inputs
        .file(docs.file("format-vectors.json"))
        .withPropertyName("vectors")
        .withPathSensitivity(PathSensitivity.RELATIVE)
    inputs
        .dir(docs.dir("vectors"))
        .withPropertyName("objects")
        .withPathSensitivity(PathSensitivity.RELATIVE)
    systemProperty("vectors", docs.file("format-vectors.json").asFile.absolutePath)
}
