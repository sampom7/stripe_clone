package com.stripeclone;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.kafka.annotation.EnableKafka;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.transaction.annotation.EnableTransactionManagement;

@SpringBootApplication
@EnableKafka
@EnableAsync
@EnableTransactionManagement
public class StripeCloneApplication {

    public static void main(String[] args) {
        SpringApplication.run(StripeCloneApplication.class, args);
    }
}

