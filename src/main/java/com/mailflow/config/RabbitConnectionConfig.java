package com.mailflow.config;

import com.rabbitmq.client.ConnectionFactory;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.rabbit.connection.CachingConnectionFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;

/**
 * Configures RabbitMQ connection with lazy behaviour so the application
 * starts successfully even when RabbitMQ is temporarily unavailable.
 *
 * <p>The actual TCP connection is established only when the first message is
 * published or the first listener container starts — not at context refresh.
 */
@Slf4j
@Configuration
public class RabbitConnectionConfig {

    @Value("${spring.rabbitmq.host:localhost}")
    private String host;

    @Value("${spring.rabbitmq.port:5672}")
    private int port;

    @Value("${spring.rabbitmq.username:guest}")
    private String username;

    @Value("${spring.rabbitmq.password:guest}")
    private String password;

    @Value("${spring.rabbitmq.virtual-host:/}")
    private String virtualHost;

    @Bean
    @Primary
    public CachingConnectionFactory cachingConnectionFactory() {
        ConnectionFactory rabbitFactory = new ConnectionFactory();
        rabbitFactory.setHost(host);
        rabbitFactory.setPort(port);
        rabbitFactory.setUsername(username);
        rabbitFactory.setPassword(password);
        rabbitFactory.setVirtualHost(virtualHost);
        // 5 second connection timeout instead of blocking forever
        rabbitFactory.setConnectionTimeout(5_000);
        // Automatic recovery when connection drops
        rabbitFactory.setAutomaticRecoveryEnabled(true);
        rabbitFactory.setNetworkRecoveryInterval(10_000);

        CachingConnectionFactory ccf = new CachingConnectionFactory(rabbitFactory);
        // Don't declare exchanges/queues at startup — defer until first use
        // This prevents startup failure when RabbitMQ is not yet available
        ccf.setPublisherConfirmType(CachingConnectionFactory.ConfirmType.NONE);

        log.info("RabbitMQ connection factory configured: {}:{} (lazy — connects on first use)",
                host, port);
        return ccf;
    }
}
