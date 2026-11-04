plugins {
    id("io.github.siloverse.jvm-library")
    application
}

application {
    mainClass.set("io.github.siloverse.loadgen.MainKt")
}

// The load generator is a tool beside the monolith, not a module of it: it drives the
// system from outside through the same adapters a client would use.
dependencies {
    implementation(project(":orders"))
    implementation(project(":fix"))
    implementation(local.kotlinx.coroutines.core)
    implementation(local.hdrhistogram)

    testImplementation(project(":venue"))
    testImplementation(local.kotlinx.coroutines.test)
}
