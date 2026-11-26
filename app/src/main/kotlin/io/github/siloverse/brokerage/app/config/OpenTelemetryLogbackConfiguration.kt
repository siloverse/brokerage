package io.github.siloverse.brokerage.app.config

import io.opentelemetry.api.OpenTelemetry
import io.opentelemetry.instrumentation.logback.appender.v1_0.OpenTelemetryAppender
import org.springframework.beans.factory.InitializingBean
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration

@Configuration
class OpenTelemetryLogbackConfiguration {

    @Bean
    fun openTelemetryLogbackInstaller(openTelemetry: OpenTelemetry): InitializingBean =
        InitializingBean { OpenTelemetryAppender.install(openTelemetry) }
}