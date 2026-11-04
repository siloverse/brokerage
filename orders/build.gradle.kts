import io.github.siloverse.build.SiloverseBuild

plugins {
    id("io.github.siloverse.jvm-library")
    `java-test-fixtures`
}

dependencies {
    implementation(project(":fix"))
    implementation(platform(local.spring.modulith.bom))
    implementation(local.spring.modulith.starter.core)
    api(local.kotlinx.coroutines.core)

    testFixturesImplementation(platform(SiloverseBuild.platformNotation))
    testFixturesApi("org.junit.jupiter:junit-jupiter")
    testFixturesApi(local.kotlinx.coroutines.test)
}
// package-info.java only marks the api named interface; nothing for javadoc to render.
tasks.javadoc { exclude("**/package-info.java") }
