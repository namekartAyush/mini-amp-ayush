package com.namekart.auction_api.registrar;

import com.namekart.auction_api.registrar.client.DynadotRegistrarClient;
import com.namekart.auction_api.registrar.client.RegistrarClient;
import com.namekart.auction_api.registrar.config.RegistrarProperties;
import com.namekart.auction_api.registrar.controller.RegistrarController;
import com.namekart.auction_api.registrar.service.RegistrarService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
class RegistrarBeanGraphTest {

    @Autowired
    private RegistrarProperties properties;

    @Autowired
    private RegistrarClient registrarClient;

    @Autowired
    private RegistrarService registrarService;

    @Autowired
    private RegistrarController registrarController;

    @Test
    @DisplayName("Verify typed configuration properties are properly bound and validated")
    void testRegistrarPropertiesBound() {
        assertThat(properties).isNotNull();
        assertThat(properties.defaultProvider()).isEqualTo("dynadot");
        assertThat(properties.dynadot()).isNotNull();
        assertThat(properties.dynadot().baseUrl()).isEqualTo("https://api.dynadot.com/api3.json");
        assertThat(properties.dynadot().timeout()).isEqualTo(Duration.ofSeconds(5));
    }

    @Test
    @DisplayName("Verify bean graph wires cleanly through constructor injection")
    void testBeanGraphWiring() {
        assertThat(registrarClient).isNotNull();
        assertThat(registrarClient).isInstanceOf(DynadotRegistrarClient.class);
        assertThat(registrarService).isNotNull();
        assertThat(registrarController).isNotNull();

        assertThat(registrarService.getDefaultProvider()).isEqualTo("dynadot");
        assertThat(registrarService.checkAvailability("example.com")).isTrue();
    }
}
