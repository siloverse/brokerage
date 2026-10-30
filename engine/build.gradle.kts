plugins {
    id("io.github.siloverse.jvm-library")
}

dependencies {
    implementation(platform(local.spring.modulith.bom))
    implementation(local.spring.modulith.starter.core)
}