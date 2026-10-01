package backend.academy.linktracker.scrapper;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;

@SpringBootApplication
@ConfigurationPropertiesScan
public class ScrapperApplication {
    public static void main(String[] args) {
        var context = SpringApplication.run(ScrapperApplication.class, args);
        // The same release has a one-off migration mode, with no HTTP server or scheduler.
        if ("migrate".equals(context.getEnvironment().getProperty("app.mode"))) {
            context.close();
        }
    }
}
