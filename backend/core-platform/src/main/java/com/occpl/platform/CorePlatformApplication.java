package com.occpl.platform;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.modulith.Modulithic;

/**
 * OCCPL Core Platform: a modular monolith. Each direct sub-package is an application module;
 * see ARCHITECTURE.md §4 for the dependency rules verified by {@code ModularityTests}.
 */
@Modulithic(sharedModules = "shared")
@SpringBootApplication
public class CorePlatformApplication {

    public static void main(String[] args) {
        SpringApplication.run(CorePlatformApplication.class, args);
    }
}
