package app.nadel;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableAsync;

@SpringBootApplication
@EnableAsync
public class NadelApplication {

    public static void main(String[] args) {
        SpringApplication.run(NadelApplication.class, args);
    }
}
