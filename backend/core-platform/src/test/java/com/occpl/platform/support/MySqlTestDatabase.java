package com.occpl.platform.support;

import org.springframework.test.context.DynamicPropertyRegistry;
import org.testcontainers.mysql.MySQLContainer;

/**
 * One MySQL container shared by all integration tests in the JVM (started on first use). The image
 * defaults to production's version (Amazon RDS MySQL 8.4 LTS, decision D-14); CI also runs the
 * suite with {@code -Doccpl.test.mysql-image=mysql:9.7} so the next upgrade is already tested.
 */
public final class MySqlTestDatabase {

    /** MySQL image for integration tests; override with {@code -Doccpl.test.mysql-image=...}. */
    public static final String IMAGE = System.getProperty("occpl.test.mysql-image", "mysql:8.4");

    private static final MySQLContainer MYSQL = new MySQLContainer(IMAGE);

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
