plugins { alias(libs.plugins.kotlin.jvm) }

kotlin { jvmToolchain(17) }

dependencies {
    api(project(":domain"))
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.kage)
    implementation(libs.zxcvbn)
    implementation(libs.zip4j)

    testImplementation(testFixtures(project(":domain")))
    testImplementation(libs.junit)
    testImplementation(libs.kotlin.test.junit)
    testImplementation(libs.kotest.property)
}

tasks.test {
    useJUnit()
    // age's scrypt work factor takes 256 MiB per derivation (format-spec 5.1).
    maxHeapSize = "1g"

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
