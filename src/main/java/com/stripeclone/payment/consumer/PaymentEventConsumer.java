package com.stripeclone.payment.consumer;

import com.stripeclone.payment.event.*;
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
public class PaymentEventConsumer {
    
    private final RetryService retryService;
    
    @KafkaListener(topics = "payment-events", groupId = "stripe-clone-group")
    public void handlePaymentEvent(
            @Payload PaymentEvent event,
            @Header(KafkaHeaders.RECEIVED_KEY) String key,
            @Header(KafkaHeaders.RECEIVED_TOPIC) String topic,
            Acknowledgment acknowledgment) {
        
        try {
            log.info("Received payment event: {} for payment: {}", event.getClass().getSimpleName(), event.getPaymentId());
            
            // Process event with retry logic
            retryService.executeWithRetry(() -> {
                // Process event based on type
                if (event instanceof PaymentInitiatedEvent) {
                    handlePaymentInitiated((PaymentInitiatedEvent) event);
                } else if (event instanceof PaymentAuthorizedEvent) {
                    handlePaymentAuthorized((PaymentAuthorizedEvent) event);
                } else if (event instanceof PaymentCapturedEvent) {
                    handlePaymentCaptured((PaymentCapturedEvent) event);
                } else if (event instanceof PaymentSettledEvent) {
                    handlePaymentSettled((PaymentSettledEvent) event);
                } else if (event instanceof PaymentFailedEvent) {
                    handlePaymentFailed((PaymentFailedEvent) event);
                }
                return null;
            }, "PaymentEventProcessing-" + event.getEventId());
            
            // Acknowledge message after successful processing
            acknowledgment.acknowledge();
            log.debug("Acknowledged payment event: {}", event.getEventId());
            
        } catch (Exception e) {
            log.error("Error processing payment event: {} after retries", event.getEventId(), e);
            // Send to dead-letter queue
            retryService.sendToDLQ(topic, key, event, e);
            // Acknowledge to prevent reprocessing
            acknowledgment.acknowledge();
        }
    }
    
    private void handlePaymentInitiated(PaymentInitiatedEvent event) {
        log.info("Processing payment initiated event for payment: {}", event.getPaymentId());
        // Additional business logic can be added here
    }
    
    private void handlePaymentAuthorized(PaymentAuthorizedEvent event) {
        log.info("Processing payment authorized event for payment: {}", event.getPaymentId());
        // Additional business logic can be added here
    }
    
    private void handlePaymentCaptured(PaymentCapturedEvent event) {
        log.info("Processing payment captured event for payment: {}", event.getPaymentId());
        // Additional business logic can be added here
    }
    
    private void handlePaymentSettled(PaymentSettledEvent event) {
        log.info("Processing payment settled event for payment: {}", event.getPaymentId());
        // Additional business logic can be added here
    }
    
    private void handlePaymentFailed(PaymentFailedEvent event) {
        log.warn("Processing payment failed event for payment: {}, reason: {}", 
            event.getPaymentId(), event.getFailureReason());
        // Additional business logic can be added here
    }
}

