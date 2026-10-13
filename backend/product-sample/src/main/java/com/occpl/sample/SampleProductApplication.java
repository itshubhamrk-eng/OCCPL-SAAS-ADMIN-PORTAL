package com.occpl.sample;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;

/**
 * Sample "Notes" product. Shows how a product service integrates with the OCCPL Core Platform:
 * it accepts provisioning calls from Core, checks every user request against Core (membership and
 * entitlement) and reports usage of its limited feature ({@code notes}).
 */
@SpringBootApplication
@ConfigurationPropertiesScan
public class SampleProductApplication {

    public static void main(String[] args) {
        SpringApplication.run(SampleProductApplication.class, args);
    }
}
