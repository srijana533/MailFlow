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

    /** TLS (amqps), required by hosted brokers such as CloudAMQP on port 5671. */
    @Value("${RABBITMQ_SSL:false}")
    private boolean ssl;

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
        if (ssl) {
            try {
                // The JVM's default trust store, with hostname verification: the
                // no-argument useSslProtocol() would trust any certificate.
                rabbitFactory.useSslProtocol(javax.net.ssl.SSLContext.getDefault());
                rabbitFactory.enableHostnameVerification();
            } catch (Exception e) {
                throw new IllegalStateException("Could not enable TLS for RabbitMQ", e);
            }
        }
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
