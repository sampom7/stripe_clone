package com.stripeclone.payment.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Service;

import java.util.concurrent.CompletableFuture;
import java.util.function.Supplier;

@Service
@RequiredArgsConstructor
@Slf4j
public class RetryService {
    
    @Value("${payment.retry.max-attempts:3}")
    private int maxAttempts;
    
    @Value("${payment.retry.backoff-millis:1000}")
    private long backoffMillis;
    
    @Qualifier("dlqKafkaTemplate")
    private final KafkaTemplate<String, Object> dlqKafkaTemplate;
    
    /**
     * Executes a supplier with retry logic and exponential backoff
     */
    public <T> T executeWithRetry(Supplier<T> operation, String operationName) {
        int attempt = 0;
        Exception lastException = null;
        
        while (attempt < maxAttempts) {
            try {
                return operation.get();
            } catch (Exception e) {
                attempt++;
                lastException = e;
                
                if (attempt < maxAttempts) {
                    long delay = backoffMillis * (long) Math.pow(2, attempt - 1);
                    log.warn("Operation {} failed (attempt {}/{}), retrying in {}ms: {}", 
                        operationName, attempt, maxAttempts, delay, e.getMessage());
                    
                    try {
                        Thread.sleep(delay);
                    } catch (InterruptedException ie) {
                        Thread.currentThread().interrupt();
                        throw new RuntimeException("Retry interrupted", ie);
                    }
                } else {
                    log.error("Operation {} failed after {} attempts", operationName, maxAttempts, e);
                }
            }
        }
        
        throw new RuntimeException("Operation failed after " + maxAttempts + " attempts: " + operationName, lastException);
    }
    
    /**
     * Executes a supplier with retry logic asynchronously
     */
    public <T> CompletableFuture<T> executeWithRetryAsync(Supplier<T> operation, String operationName) {
        return CompletableFuture.supplyAsync(() -> executeWithRetry(operation, operationName));
    }
    
    /**
     * Sends a message to the dead-letter queue
     */
    public void sendToDLQ(String topic, String key, Object message, Exception error) {
        try {
            DLQMessage dlqMessage = new DLQMessage(topic, key, message, error.getMessage(), System.currentTimeMillis());
            dlqKafkaTemplate.send("dlq-" + topic, key, dlqMessage);
            log.warn("Sent message to DLQ for topic: {}, key: {}", topic, key);
        } catch (Exception e) {
            log.error("Failed to send message to DLQ", e);
        }
    }
    
    public record DLQMessage(
        String originalTopic,
        String originalKey,
        Object originalMessage,
        String errorMessage,
        long timestamp
    ) {}
}

