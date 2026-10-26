package org.remus.giteabot;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication
@EnableAsync
@EnableScheduling
public class AiGitBotApplication {

    public static void main(String[] args) {
        SpringApplication.run(AiGitBotApplication.class, args);
    }
}
