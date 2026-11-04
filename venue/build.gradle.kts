plugins {
    id("io.github.siloverse.spring-boot-application")
}

application {
    mainClass.set("io.github.siloverse.venue.VenueApplicationKt")
}

dependencies {
    implementation(project(":fix"))
    implementation(libs.bundles.spring.web)
    implementation(libs.bundles.siloverse.observability)

    // Test-only exception to "venue depends on nothing from the broker": the Venue port's
    // contract test runs here, against the broker's FIX adapter, over a real socket.
    testImplementation(project(":orders"))
    testImplementation(testFixtures(project(":orders")))
    testImplementation(local.kotlinx.coroutines.test)
}
