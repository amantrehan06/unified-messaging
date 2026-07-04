package com.messaging.channel;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class UnipileConfig {

    private static final Logger log = LoggerFactory.getLogger(UnipileConfig.class);

    @Bean
    public UnipileClient unipileClient(
            @Value("${unipile.dsn:}") String dsn,
            @Value("${unipile.api-key:}") String apiKey) {
        if (dsn != null && !dsn.isBlank()) {
            log.info("Unipile client: LIVE (dsn={})", dsn);
            return new HttpUnipileClient(dsn, apiKey);
        }
        log.info("Unipile client: STUB (no unipile.dsn configured)");
        return new StubUnipileClient();
    }
}
