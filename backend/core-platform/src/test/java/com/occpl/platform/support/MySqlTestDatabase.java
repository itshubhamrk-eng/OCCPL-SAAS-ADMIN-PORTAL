package com.occpl.platform.support;

import org.springframework.test.context.DynamicPropertyRegistry;
import org.testcontainers.mysql.MySQLContainer;

/** One MySQL 9.7 container shared by all integration tests in the JVM (started on first use). */
public final class MySqlTestDatabase {

    private static final MySQLContainer MYSQL = new MySQLContainer("mysql:9.7");

    static {
        MYSQL.start();
    }

    private MySqlTestDatabase() {
    }

    public static void register(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", MYSQL::getJdbcUrl);
        registry.add("spring.datasource.username", MYSQL::getUsername);
        registry.add("spring.datasource.password", MYSQL::getPassword);
    }
}
