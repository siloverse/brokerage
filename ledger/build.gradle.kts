plugins {
    id("io.github.siloverse.jvm-library")
    id("org.jetbrains.kotlin.plugin.spring")
}

dependencies {
    implementation(platform(local.spring.modulith.bom))
    implementation(local.spring.modulith.starter.core)
    implementation(local.spring.boot.starter.jdbc)
    implementation(local.flyway.core)
    runtimeOnly(local.flyway.postgresql)
    runtimeOnly(local.postgresql)
}
// package-info.java only marks the api named interface; nothing for javadoc to render.
tasks.javadoc { exclude("**/package-info.java") }
