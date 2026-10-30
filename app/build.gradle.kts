plugins {
    id("io.github.siloverse.spring-boot-application")
}

application {
    mainClass.set("io.github.siloverse.brokerage.BrokerageApplicationKt")
}

dependencies {
    implementation(project(":ledger"))
    implementation(project(":orders"))
    implementation(project(":engine"))
    implementation(project(":marketdata"))
    implementation(project(":portfolio"))

    implementation(platform(local.spring.modulith.bom))
    implementation(local.spring.modulith.starter.core)
    implementation(libs.bundles.spring.web)
    implementation(libs.bundles.siloverse.observability)

    testImplementation(local.spring.modulith.starter.test)
    testImplementation(local.archunit.junit5)
}