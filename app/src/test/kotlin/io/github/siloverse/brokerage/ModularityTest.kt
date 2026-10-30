package io.github.siloverse.brokerage

import org.junit.jupiter.api.Test
import org.springframework.modulith.core.ApplicationModules

class ModularityTest {

    private val modules = ApplicationModules.of(BrokerageApplication::class.java)

    @Test
    fun `modules respect their boundaries`() {
        modules.verify()
    }

    @Test
    fun `print the module map`() {
        modules.forEach(::println)
    }
}
