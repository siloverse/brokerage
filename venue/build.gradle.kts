plugins {
    id("io.github.siloverse.spring-boot-application")
}

application {
    mainClass.set("io.github.siloverse.venue.VenueApplicationKt")
}

dependencies {

    implementation(platform(local.spring.modulith.bom))
    implementation(local.spring.modulith.starter.core)
    implementation(libs.bundles.spring.web)
    implementation(libs.bundles.siloverse.observability)

    testImplementation(local.spring.modulith.starter.test)
    testImplementation(local.archunit.junit5)
}