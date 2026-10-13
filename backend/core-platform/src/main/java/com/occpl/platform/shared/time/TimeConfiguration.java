package com.occpl.platform.shared.time;

import java.time.Clock;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** All timestamps are UTC; inject {@link Clock} instead of calling {@code now()} directly. */
@Configuration(proxyBeanMethods = false)
class TimeConfiguration {

    @Bean
    Clock clock() {
        return Clock.systemUTC();
    }
}
