package org.example.tgmail;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication
@EnableScheduling
public class TgMailApplication {

    public static void main(String[] args) {
        SpringApplication.run(TgMailApplication.class, args);
    }
}
