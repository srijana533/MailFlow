package com.mailflow.email;

import com.mailflow.config.MailFlowProperties;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.data.redis.core.script.DefaultRedisScript;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.when;

/**
 * Unit tests for the Redis token-bucket rate limiter.
 */
@ExtendWith(MockitoExtension.class)
class RateLimiterServiceTest {

    @Mock private RedisTemplate<String, Object> redisTemplate;
    @Mock private ValueOperations<String, Object> valueOps;

    private RateLimiterService rateLimiter;

    @BeforeEach
    void setUp() {
        MailFlowProperties props = new MailFlowProperties();
        props.setEmail(new MailFlowProperties.Email());
        props.getEmail().setRateLimitPerMinute(100);

        rateLimiter = new RateLimiterService(redisTemplate, props);
    }

    @Test
    void allows_request_when_within_limit() {
        when(redisTemplate.execute(any(DefaultRedisScript.class), anyList())).thenReturn(50L);
        assertThat(rateLimiter.tryAcquire(1L)).isTrue();
    }

    @Test
    void blocks_request_when_limit_exceeded() {
        when(redisTemplate.execute(any(DefaultRedisScript.class), anyList())).thenReturn(101L);
        assertThat(rateLimiter.tryAcquire(1L)).isFalse();
    }

    @Test
    void allows_request_exactly_at_limit() {
        when(redisTemplate.execute(any(DefaultRedisScript.class), anyList())).thenReturn(100L);
        assertThat(rateLimiter.tryAcquire(1L)).isTrue();
    }

    @Test
    void fails_open_when_redis_throws() {
        when(redisTemplate.execute(any(DefaultRedisScript.class), anyList()))
            .thenThrow(new RuntimeException("Redis down"));
        // Should fail open (allow) to avoid blocking campaigns
        assertThat(rateLimiter.tryAcquire(1L)).isTrue();
    }
}
