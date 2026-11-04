package io.github.siloverse.brokerage

import com.tngtech.archunit.core.importer.ClassFileImporter
import com.tngtech.archunit.core.importer.ImportOption
import com.tngtech.archunit.lang.syntax.ArchRuleDefinition.classes
import com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses
import org.junit.jupiter.api.Test

/**
 * The layers inside every business module (docs/architecture.md). Modulith guards the
 * walls between modules; these rules guard the shape inside one.
 */
class LayersTest {

    private val base = "io.github.siloverse.brokerage"
    private val classes = ClassFileImporter().withImportOption(ImportOption.DoNotIncludeTests()).importPackages(base)

    @Test
    fun `domain depends on no other layer and no framework`() {
        noClasses().that().resideInAPackage("$base..domain..")
            .should().dependOnClassesThat().resideInAnyPackage("$base..api..", "$base..application..", "$base..infrastructure..", "org.springframework..", "jakarta.persistence..")
            .check(classes)
    }

    @Test
    fun `application never reaches into infrastructure`() {
        noClasses().that().resideInAPackage("$base..application..")
            .should().dependOnClassesThat().resideInAPackage("$base..infrastructure..")
            .check(classes)
    }

    @Test
    fun `api exposes only its own types`() {
        classes().that().resideInAPackage("$base..api..")
            .should().onlyDependOnClassesThat().resideInAnyPackage("$base..api..", "java..", "kotlin..", "kotlinx..", "org.jetbrains..", "org.springframework.modulith..")
            .allowEmptyShould(true)
            .check(classes)
    }
}
