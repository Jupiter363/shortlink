package com.jupiter.shortlink.redirect;

import com.jupiter.shortlink.redirect.config.RedirectProperties;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;

@SpringBootApplication
@EnableConfigurationProperties({
    RedirectProperties.class,
    com.jupiter.shortlink.redirect.config.KafkaTransportProperties.class
})
public class RedirectApplication {
    public static void main(String[] args) {
        SpringApplication.run(RedirectApplication.class, args);
    }
}
