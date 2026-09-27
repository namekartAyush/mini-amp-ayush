package com.namekart.auction_api.config;

import com.namekart.auction_api.common.config.DatasourcePoolProperties;
import com.namekart.auction_api.registrar.config.RegistrarProperties;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.autoconfigure.jdbc.DataSourceAutoConfiguration;
import org.springframework.boot.autoconfigure.jdbc.DataSourceTransactionManagerAutoConfiguration;
import org.springframework.boot.autoconfigure.orm.jpa.HibernateJpaAutoConfiguration;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.annotation.Configuration;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Empirical proof of configuration precedence hierarchy and fail-fast validation.
 * Precedence Order: CLI Argument > Environment / System Property > Profile File > Default
 */
class ConfigurationPrecedenceTest {

    @Configuration
    @EnableAutoConfiguration(exclude = {
            DataSourceAutoConfiguration.class,
            HibernateJpaAutoConfiguration.class,
            DataSourceTransactionManagerAutoConfiguration.class
    })
    @EnableConfigurationProperties({RegistrarProperties.class, DatasourcePoolProperties.class})
    static class TestConfig {}

    @Test
    @DisplayName("Verify CLI argument overrides Environment/System Property and Profile file")
    void testCliArgumentWinsOverEnvAndProfile() {
        System.setProperty("registrar.dynadot.timeout", "8s");

        try (ConfigurableApplicationContext context = new SpringApplicationBuilder(TestConfig.class)
                .web(org.springframework.boot.WebApplicationType.NONE)
                .profiles("dev")
                .run("--registrar.dynadot.timeout=2s")) {

            RegistrarProperties props = context.getBean(RegistrarProperties.class);
            System.out.println("==================================================");
            System.out.println("PRECEDENCE PROOF (CLI vs System/Env vs Profile):");
            System.out.println("Configured in profile file: 5s");
            System.out.println("Configured in System/Env: 8s");
            System.out.println("Configured via CLI arg: --registrar.dynadot.timeout=2s");
            System.out.println("WINNING VALUE: " + props.dynadot().timeout());
            System.out.println("==================================================");

            // CLI argument MUST win
            assertThat(props.dynadot().timeout()).isEqualTo(Duration.ofSeconds(2));
        } finally {
            System.clearProperty("registrar.dynadot.timeout");
        }
    }

    @Test
    @DisplayName("Verify Environment/System Property overrides Profile file when CLI argument is absent")
    void testEnvPropertyWinsOverProfileFile() {
        System.setProperty("registrar.dynadot.timeout", "7s");

        try (ConfigurableApplicationContext context = new SpringApplicationBuilder(TestConfig.class)
                .web(org.springframework.boot.WebApplicationType.NONE)
                .profiles("dev")
                .run()) {

            RegistrarProperties props = context.getBean(RegistrarProperties.class);
            System.out.println("==================================================");
            System.out.println("PRECEDENCE PROOF (System/Env vs Profile):");
            System.out.println("Configured in profile file: 5s");
            System.out.println("Configured in System/Env: 7s");
            System.out.println("WINNING VALUE: " + props.dynadot().timeout());
            System.out.println("==================================================");

            // System/Env property MUST win over profile file
            assertThat(props.dynadot().timeout()).isEqualTo(Duration.ofSeconds(7));
        } finally {
            System.clearProperty("registrar.dynadot.timeout");
        }
    }

    @Test
    @DisplayName("Verify Profile file value is used when neither CLI nor Environment overrides it")
    void testProfileFileUsedWhenNoOverrides() {
        try (ConfigurableApplicationContext context = new SpringApplicationBuilder(TestConfig.class)
                .web(org.springframework.boot.WebApplicationType.NONE)
                .profiles("dev")
                .run()) {

            RegistrarProperties props = context.getBean(RegistrarProperties.class);
            System.out.println("==================================================");
            System.out.println("PRECEDENCE PROOF (Baseline Profile File):");
            System.out.println("WINNING VALUE FROM dev profile: " + props.dynadot().timeout());
            System.out.println("==================================================");

            assertThat(props.dynadot().timeout()).isEqualTo(Duration.ofSeconds(5));
        }
    }

    @Test
    @DisplayName("Fail-Fast: App refuses to start when connection pool size is out of valid bounds (< 2)")
    void testFailFastOnInvalidPoolSize() {
        assertThatThrownBy(() -> new SpringApplicationBuilder(TestConfig.class)
                .web(org.springframework.boot.WebApplicationType.NONE)
                .profiles("dev")
                .run("--app.datasource.pool-size=1"))
                .satisfies(ex -> assertThat(ex.getMessage()).contains("Could not bind properties"));
    }

    @Test
    @DisplayName("Fail-Fast: App refuses to start when required registrar API key is blank")
    void testFailFastOnBlankApiKey() {
        assertThatThrownBy(() -> new SpringApplicationBuilder(TestConfig.class)
                .web(org.springframework.boot.WebApplicationType.NONE)
                .profiles("dev")
                .run("--registrar.dynadot.api-key="))
                .satisfies(ex -> assertThat(ex.getMessage()).contains("Could not bind properties"));
    }
}
