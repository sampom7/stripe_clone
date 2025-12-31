package com.stripeclone.payment.consumer;

import com.stripeclone.payment.service.RetryService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.support.Acknowledgment;
import org.springframework.kafka.support.KafkaHeaders;
import org.springframework.messaging.handler.annotation.Header;
import org.springframework.messaging.handler.annotation.Payload;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
@Slf4j
public class DLQConsumer {
    
    @KafkaListener(topics = "dlq-payment-events", groupId = "stripe-clone-dlq-group")
    public void handleDLQMessage(
            @Payload RetryService.DLQMessage dlqMessage,
            @Header(KafkaHeaders.RECEIVED_KEY) String key,
            Acknowledgment acknowledgment) {
        
        log.error("Processing DLQ message - Original Topic: {}, Key: {}, Error: {}, Timestamp: {}", 
            dlqMessage.originalTopic(), 
            dlqMessage.originalKey(), 
            dlqMessage.errorMessage(),
            dlqMessage.timestamp());
        
        // In production, you would:
        // 1. Store in a database for manual review
        // 2. Send alerts to monitoring systems
        // 3. Attempt manual reprocessing
        
        // For now, just log and acknowledge
        acknowledgment.acknowledge();
    }
}

