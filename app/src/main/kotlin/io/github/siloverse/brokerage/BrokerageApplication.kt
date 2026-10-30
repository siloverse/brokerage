package io.github.siloverse.brokerage

import org.springframework.boot.autoconfigure.SpringBootApplication
import org.springframework.boot.runApplication

@SpringBootApplication
class BrokerageApplication

fun main(args: Array<String>) {
    runApplication<BrokerageApplication>(*args)
}