package com.streamflix.analytics.config;

import org.flywaydb.core.Flyway;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.jdbc.DataSourceBuilder;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.core.JdbcTemplate;

import javax.sql.DataSource;

/**
 * Two datasources: the OLTP store (primary — used by JPA/Flyway for the raw event table) and the
 * OLAP star-schema database (read/aggregate side). The OLAP schema is migrated programmatically
 * from {@code db/olap} so it stays independent of the primary Flyway run.
 */
@Configuration
public class DataSourceConfig {

    @Bean
    @Primary
    public DataSource oltpDataSource(
            @Value("${spring.datasource.url}") String url,
            @Value("${spring.datasource.username}") String username,
            @Value("${spring.datasource.password}") String password) {
        return DataSourceBuilder.create().url(url).username(username).password(password).build();
    }

    @Bean
    public DataSource olapDataSource(
            @Value("${olap.datasource.url}") String url,
            @Value("${olap.datasource.username}") String username,
            @Value("${olap.datasource.password}") String password) {
        return DataSourceBuilder.create().url(url).username(username).password(password).build();
    }

    @Bean
    @Primary
    public JdbcTemplate oltpJdbcTemplate(@Qualifier("oltpDataSource") DataSource ds) {
        return new JdbcTemplate(ds);
    }

    @Bean
    public JdbcTemplate olapJdbcTemplate(@Qualifier("olapDataSource") DataSource ds) {
        return new JdbcTemplate(ds);
    }

    /**
     * Declaring a Flyway bean (olapFlyway, below) disables Spring Boot's auto Flyway, so we also
     * migrate the OLTP raw event-store schema explicitly here.
     */
    @Bean(initMethod = "migrate")
    public Flyway oltpFlyway(@Qualifier("oltpDataSource") DataSource ds,
                             @Value("${spring.flyway.default-schema:analytics}") String schema) {
        return Flyway.configure()
                .dataSource(ds)
                .schemas(schema)
                .defaultSchema(schema)
                .createSchemas(true)
                .baselineOnMigrate(true)
                .locations("classpath:db/migration")
                .load();
    }

    @Bean(initMethod = "migrate")
    public Flyway olapFlyway(@Qualifier("olapDataSource") DataSource ds) {
        return Flyway.configure()
                .dataSource(ds)
                .locations("classpath:db/olap")
                .table("flyway_schema_history_olap")
                .load();
    }
}
