package com.messaging.channel;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class UnipileConfig {

    @Bean
    public UnipileClient unipileClient(
            @Value("${unipile.dsn}") String dsn,
            @Value("${unipile.api-key}") String apiKey) {
        return new HttpUnipileClient(dsn, apiKey);
    }
}
