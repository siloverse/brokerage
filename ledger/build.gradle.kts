plugins {
    id("io.github.siloverse.jvm-library")
    id("org.jetbrains.kotlin.plugin.spring")
}

dependencies {
    implementation(platform(local.spring.modulith.bom))
    implementation(local.spring.modulith.starter.core)
    implementation(platform(local.siloverse.identifiers.bom))
    implementation(local.siloverse.identifiers.core)
    implementation(local.spring.boot.starter.jdbc)
    implementation(local.flyway.core)
    runtimeOnly(local.flyway.postgresql)
    runtimeOnly(local.postgresql)
    implementation(libs.bundles.spring.web)
    implementation(kotlin("reflect"))
    implementation(local.siloverse.messaging.core)
    implementation(local.micrometer.core)

    testImplementation(local.spring.boot.starter.test)
    testImplementation(local.mockito.kotlin)
    testImplementation(local.siloverse.messaging.spring)
    testImplementation(local.spring.boot.webmvc.test)
    testImplementation(local.spring.boot.testcontainers)
    testImplementation(libs.testcontainers.postgresql)
}
// package-info.java only marks the api named interface; nothing for javadoc to render.
tasks.javadoc { exclude("**/package-info.java") }
