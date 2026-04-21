package dev.openchoreo.claims;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.ApplicationListener;
import org.springframework.context.event.ContextRefreshedEvent;
import org.springframework.stereotype.Component;

@SpringBootApplication
public class ClaimsApplication {

    private static final Logger log = LoggerFactory.getLogger(ClaimsApplication.class);

    public static void main(String[] args) {
        SpringApplication.run(ClaimsApplication.class, args);
    }

    @Component
    static class StartupLogger implements ApplicationListener<ContextRefreshedEvent> {
        @Override
        public void onApplicationEvent(ContextRefreshedEvent event) {
            log.info("Claims service started and ready to accept requests");
        }
    }
}
