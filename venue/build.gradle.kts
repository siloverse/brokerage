plugins {
    id("io.github.siloverse.spring-boot-application")
}

application {
    mainClass.set("io.github.siloverse.venue.VenueApplicationKt")
}

dependencies {
    implementation(libs.bundles.spring.web)
    implementation(libs.bundles.siloverse.observability)
}