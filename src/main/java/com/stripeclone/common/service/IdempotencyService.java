package com.stripeclone.common.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.Optional;

@Service
@RequiredArgsConstructor
@Slf4j
public class IdempotencyService {
    
    private final RedisTemplate<String, String> redisTemplate;
    private final ObjectMapper objectMapper;
    
    @Value("${payment.idempotency.ttl-seconds:86400}")
    private long idempotencyTtlSeconds;
    
    private static final String IDEMPOTENCY_KEY_PREFIX = "idempotency:";
    
    public <T> Optional<T> getCachedResponse(String idempotencyKey, Class<T> responseType) {
        try {
            String key = IDEMPOTENCY_KEY_PREFIX + idempotencyKey;
            String cachedValue = redisTemplate.opsForValue().get(key);
            
            if (cachedValue != null) {
                log.debug("Found cached response for idempotency key: {}", idempotencyKey);
                return Optional.of(objectMapper.readValue(cachedValue, responseType));
            }
            
            return Optional.empty();
        } catch (Exception e) {
            log.error("Error retrieving idempotency cache for key: {}", idempotencyKey, e);
            return Optional.empty();
        }
    }
    
    public <T> void cacheResponse(String idempotencyKey, T response) {
        try {
            String key = IDEMPOTENCY_KEY_PREFIX + idempotencyKey;
            String value = objectMapper.writeValueAsString(response);
            
            redisTemplate.opsForValue().set(
                key, 
                value, 
                Duration.ofSeconds(idempotencyTtlSeconds)
            );
            
            log.debug("Cached response for idempotency key: {}", idempotencyKey);
        } catch (Exception e) {
            log.error("Error caching idempotency response for key: {}", idempotencyKey, e);
        }
    }
    
    public boolean isProcessing(String idempotencyKey) {
        try {
            String key = IDEMPOTENCY_KEY_PREFIX + "processing:" + idempotencyKey;
            return Boolean.TRUE.equals(redisTemplate.hasKey(key));
        } catch (Exception e) {
            log.error("Error checking processing status for key: {}", idempotencyKey, e);
            return false;
        }
    }
    
    public void markAsProcessing(String idempotencyKey) {
        try {
            String key = IDEMPOTENCY_KEY_PREFIX + "processing:" + idempotencyKey;
            redisTemplate.opsForValue().set(
                key, 
                "true", 
                Duration.ofSeconds(300) // 5 minutes max processing time
            );
        } catch (Exception e) {
            log.error("Error marking as processing for key: {}", idempotencyKey, e);
        }
    }
    
    public void clearProcessing(String idempotencyKey) {
        try {
            String key = IDEMPOTENCY_KEY_PREFIX + "processing:" + idempotencyKey;
            redisTemplate.delete(key);
        } catch (Exception e) {
            log.error("Error clearing processing status for key: {}", idempotencyKey, e);
        }
    }
}

