plugins {
    id("io.github.siloverse.jvm-library")
}

dependencies {
    implementation(project(":ledger"))

    implementation(platform(local.spring.modulith.bom))

    implementation(local.siloverse.messaging.core)
    implementation("org.springframework:spring-context")
    implementation(local.spring.modulith.starter.core)
}
// package-info.java only marks the api named interface; nothing for javadoc to render.
tasks.javadoc { exclude("**/package-info.java") }
