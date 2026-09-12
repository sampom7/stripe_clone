package com.stripeclone;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication
@EnableScheduling
public class StripeCloneApplication {

    public static void main(String[] args) {
        SpringApplication.run(StripeCloneApplication.class, args);
    }
}
