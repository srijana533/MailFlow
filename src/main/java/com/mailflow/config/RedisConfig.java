package com.mailflow.config;

import java.time.Duration;
import java.util.Map;

import org.springframework.cache.CacheManager;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.cache.RedisCacheConfiguration;
import org.springframework.data.redis.cache.RedisCacheManager;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.serializer.GenericJackson2JsonRedisSerializer;
import org.springframework.data.redis.serializer.RedisSerializationContext;
import org.springframework.data.redis.serializer.StringRedisSerializer;

import com.fasterxml.jackson.annotation.JsonTypeInfo;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.jsontype.impl.LaissezFaireSubTypeValidator;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;

/**
 * Redis configuration.
 *
 * <p>Used for:
 * <ul>
 *   <li>Rate limiting (token bucket counters per minute window)</li>
 *   <li>Idempotency keys (dedup email delivery)</li>
 *   <li>General caching (templates, campaign configs)</li>
 * </ul>
 */
@Configuration
public class RedisConfig {

    /**
     * Builds an ObjectMapper for Redis (de)serialization. Registers the
     * JSR-310 module so {@code java.time} types such as {@link java.time.Instant}
     * (present on cached DTOs like CampaignResponse.createdAt) serialize as ISO
     * strings instead of failing. Default typing is enabled so polymorphic
     * cache values round-trip correctly.
     */
    private static ObjectMapper redisObjectMapper() {
        ObjectMapper mapper = new ObjectMapper();
        mapper.registerModule(new JavaTimeModule());
        mapper.disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
        // EVERYTHING (rather than NON_FINAL) forces the @class type id to be
        // written at the document root as well, including for record/DTO values.
        // Without a root @class, GenericJackson2JsonRedisSerializer cannot
        // resolve the type on read and fails with "missing type id property
        // '@class'". NON_FINAL omits the root id for the top-level value.
        mapper.activateDefaultTyping(
            LaissezFaireSubTypeValidator.instance,
            ObjectMapper.DefaultTyping.EVERYTHING,
            JsonTypeInfo.As.PROPERTY
        );
        return mapper;
    }

    @Bean
    public RedisTemplate<String, Object> redisTemplate(RedisConnectionFactory factory) {
        GenericJackson2JsonRedisSerializer serializer =
            new GenericJackson2JsonRedisSerializer(redisObjectMapper());
        RedisTemplate<String, Object> template = new RedisTemplate<>();
        template.setConnectionFactory(factory);
        template.setKeySerializer(new StringRedisSerializer());
        template.setHashKeySerializer(new StringRedisSerializer());
        template.setValueSerializer(serializer);
        template.setHashValueSerializer(serializer);
        template.afterPropertiesSet();
        return template;
    }

    @Bean
    public CacheManager cacheManager(RedisConnectionFactory factory) {
        GenericJackson2JsonRedisSerializer serializer =
            new GenericJackson2JsonRedisSerializer(redisObjectMapper());

        RedisCacheConfiguration defaultConfig = RedisCacheConfiguration.defaultCacheConfig()
                .entryTtl(Duration.ofMinutes(10))
                .serializeKeysWith(
                    RedisSerializationContext.SerializationPair.fromSerializer(new StringRedisSerializer()))
                .serializeValuesWith(
                    RedisSerializationContext.SerializationPair.fromSerializer(serializer))
                .disableCachingNullValues();

        // Per-cache TTL overrides
        Map<String, RedisCacheConfiguration> cacheConfigs = Map.of(
            "templates",    defaultConfig.entryTtl(Duration.ofMinutes(30)),
            "campaigns",    defaultConfig.entryTtl(Duration.ofMinutes(5)),
            "recipients",   defaultConfig.entryTtl(Duration.ofMinutes(15)),
            // Unsubscribe status cached for 1 day — the @CacheEvict in UnsubscribeService
            // handles immediate invalidation when a user opts out.
            "unsubscribed", defaultConfig.entryTtl(Duration.ofDays(1))
        );

        return RedisCacheManager.builder(factory)
                .cacheDefaults(defaultConfig)
                .withInitialCacheConfigurations(cacheConfigs)
                .build();
    }
}
