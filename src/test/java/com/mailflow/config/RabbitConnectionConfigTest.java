package com.mailflow.config;

import org.junit.jupiter.api.Test;
import org.springframework.amqp.rabbit.connection.CachingConnectionFactory;
import org.springframework.test.util.ReflectionTestUtils;

import static org.assertj.core.api.Assertions.assertThat;

class RabbitConnectionConfigTest {

    @Test
    void pasted_whitespace_around_the_connection_settings_is_removed() {
        // A tab before the host broke the first hosted deploy: "UnknownHostException: \tshark...".
        RabbitConnectionConfig config = new RabbitConnectionConfig();
        ReflectionTestUtils.setField(config, "host", "\tshark.rmq.cloudamqp.com \n");
        ReflectionTestUtils.setField(config, "port", 5671);
        ReflectionTestUtils.setField(config, "username", " user\t");
        ReflectionTestUtils.setField(config, "password", "secret\n");
        ReflectionTestUtils.setField(config, "virtualHost", "\tuser");
        ReflectionTestUtils.setField(config, "ssl", false);

        CachingConnectionFactory ccf = config.cachingConnectionFactory();

        assertThat(ccf.getHost()).isEqualTo("shark.rmq.cloudamqp.com");
        assertThat(ccf.getUsername()).isEqualTo("user");
        assertThat(ccf.getVirtualHost()).isEqualTo("user");
        assertThat(ccf.getRabbitConnectionFactory().getPassword()).isEqualTo("secret");
    }
}
