package com.mailflow.email;

import com.mailflow.config.MailFlowProperties;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * Token-bucket rate limiter backed by Redis.
 *
 * <p>Uses an atomic Lua script so increment + check is a single Redis operation,
 * avoiding race conditions under concurrent workers.
 *
 * <p>Each campaign has an independent rate-limit window of 60 seconds.
 * Limit is set by {@code mailflow.email.rate-limit-per-minute}.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class RateLimiterService {

    private static final String KEY_PREFIX = "ratelimit:campaign:";

    private static final String LUA_SCRIPT = """
            local current = redis.call('INCR', KEYS[1])
            if current == 1 then
                redis.call('EXPIRE', KEYS[1], 60)
            end
            return current
            """;

    private final RedisTemplate<String, Object> redisTemplate;
    private final MailFlowProperties properties;

    /**
     * Attempts to acquire a send slot for the given campaign.
     *
     * @return {@code true} if within the rate limit, {@code false} otherwise
     */
    public boolean tryAcquire(Long campaignId) {
        String key = KEY_PREFIX + campaignId;
        int limit  = properties.getEmail().getRateLimitPerMinute();

        try {
            DefaultRedisScript<Long> script = new DefaultRedisScript<>(LUA_SCRIPT, Long.class);
            Long count = redisTemplate.execute(script, List.of(key));

            if (count != null && count <= limit) {
                return true;
            }
            log.debug("Rate limit hit for campaign {}: count={}, limit={}", campaignId, count, limit);
            return false;
        } catch (Exception e) {
            // If Redis is unavailable, fail open (allow sending) to avoid blocking campaigns
            log.warn("Rate limiter Redis error — failing open: {}", e.getMessage());
            return true;
        }
    }

    /**
     * Returns the current send count in the active window.
     */
    public long getCurrentCount(Long campaignId) {
        String key = KEY_PREFIX + campaignId;
        Object val = redisTemplate.opsForValue().get(key);
        return val != null ? Long.parseLong(val.toString()) : 0L;
    }
}
