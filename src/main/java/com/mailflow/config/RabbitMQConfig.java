package com.mailflow.config;

import org.springframework.amqp.core.*;
import org.springframework.amqp.rabbit.config.RetryInterceptorBuilder;
import org.springframework.amqp.rabbit.config.SimpleRabbitListenerContainerFactory;
import org.springframework.amqp.rabbit.connection.ConnectionFactory;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.amqp.rabbit.retry.RejectAndDontRequeueRecoverer;
import org.springframework.amqp.support.converter.Jackson2JsonMessageConverter;
import org.springframework.amqp.support.converter.MessageConverter;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.retry.interceptor.RetryOperationsInterceptor;

/**
 * RabbitMQ topology:
 *
 * <pre>
 *   email.exchange (direct)
 *       ├── email.queue            ← main processing queue
 *       └── email.dead-letter.queue ← permanent failures land here
 * </pre>
 *
 * Messages rejected after max retries are routed to the dead-letter queue
 * without being requeued, enabling manual inspection and alerting.
 */
@Configuration
public class RabbitMQConfig {

    // ── Exchange names ─────────────────────────────────────────────────────
    public static final String EMAIL_EXCHANGE        = "email.exchange";
    public static final String EMAIL_DL_EXCHANGE     = "email.dead-letter.exchange";

    // ── Queue names ────────────────────────────────────────────────────────
    public static final String EMAIL_QUEUE           = "email.queue";
    public static final String EMAIL_DL_QUEUE        = "email.dead-letter.queue";

    // ── Routing keys ───────────────────────────────────────────────────────
    public static final String EMAIL_ROUTING_KEY    = "email.send";
    public static final String EMAIL_DL_ROUTING_KEY = "email.dead";

    // ── Exchanges ──────────────────────────────────────────────────────────
    @Bean
    public DirectExchange emailExchange() {
        return ExchangeBuilder.directExchange(EMAIL_EXCHANGE)
                .durable(true)
                .build();
    }

    @Bean
    public DirectExchange emailDeadLetterExchange() {
        return ExchangeBuilder.directExchange(EMAIL_DL_EXCHANGE)
                .durable(true)
                .build();
    }

    // ── Queues ─────────────────────────────────────────────────────────────
    @Bean
    public Queue emailQueue() {
        return QueueBuilder.durable(EMAIL_QUEUE)
                .withArgument("x-dead-letter-exchange", EMAIL_DL_EXCHANGE)
                .withArgument("x-dead-letter-routing-key", EMAIL_DL_ROUTING_KEY)
                .withArgument("x-message-ttl", 86_400_000) // 24h TTL
                .build();
    }

    @Bean
    public Queue emailDeadLetterQueue() {
        return QueueBuilder.durable(EMAIL_DL_QUEUE)
                .build();
    }

    // ── Bindings ───────────────────────────────────────────────────────────
    @Bean
    public Binding emailQueueBinding(Queue emailQueue, DirectExchange emailExchange) {
        return BindingBuilder.bind(emailQueue).to(emailExchange).with(EMAIL_ROUTING_KEY);
    }

    @Bean
    public Binding emailDeadLetterBinding(Queue emailDeadLetterQueue,
                                          DirectExchange emailDeadLetterExchange) {
        return BindingBuilder.bind(emailDeadLetterQueue)
                .to(emailDeadLetterExchange)
                .with(EMAIL_DL_ROUTING_KEY);
    }

    // ── Serialization ──────────────────────────────────────────────────────
    @Bean
    public MessageConverter jsonMessageConverter() {
        return new Jackson2JsonMessageConverter();
    }

    // ── RabbitTemplate ─────────────────────────────────────────────────────
    @Bean
    public RabbitTemplate rabbitTemplate(ConnectionFactory connectionFactory) {
        RabbitTemplate template = new RabbitTemplate(connectionFactory);
        template.setMessageConverter(jsonMessageConverter());
        template.setExchange(EMAIL_EXCHANGE);
        return template;
    }

    // ── Listener container with retry ──────────────────────────────────────
    @Bean
    public RetryOperationsInterceptor retryInterceptor() {
        return RetryInterceptorBuilder.stateless()
                .maxAttempts(3)
                .backOffOptions(2_000, 2.0, 10_000) // 2s → 4s → 8s
                .recoverer(new RejectAndDontRequeueRecoverer())
                .build();
    }

    @Bean
    public SimpleRabbitListenerContainerFactory rabbitListenerContainerFactory(
            ConnectionFactory connectionFactory) {
        SimpleRabbitListenerContainerFactory factory = new SimpleRabbitListenerContainerFactory();
        factory.setConnectionFactory(connectionFactory);
        factory.setMessageConverter(jsonMessageConverter());
        factory.setAdviceChain(retryInterceptor());
        factory.setPrefetchCount(10);
        return factory;
    }
}
