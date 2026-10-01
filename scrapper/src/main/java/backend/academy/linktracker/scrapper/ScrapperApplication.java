package backend.academy.linktracker.scrapper;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;

@SpringBootApplication
@ConfigurationPropertiesScan
public class ScrapperApplication {
    public static void main(String[] args) {
        var context = SpringApplication.run(ScrapperApplication.class, args);
        // Administrative modes use the same release and finish after their one-off task.
        if (java.util.Set.of("migrate", "assign-legacy").contains(context.getEnvironment().getProperty("app.mode", "web"))) {
            context.close();
        }
    }
}
