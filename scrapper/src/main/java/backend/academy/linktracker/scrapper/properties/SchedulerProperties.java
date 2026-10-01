package backend.academy.linktracker.scrapper.properties;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

@Validated
@ConfigurationProperties("app.scheduler")
public record SchedulerProperties(boolean enabled, Duration interval, @Min(1) @Max(100) int batchSize) {
    public SchedulerProperties {
        if (interval == null || interval.compareTo(Duration.ofSeconds(1)) < 0) {
            throw new IllegalArgumentException("Scheduler interval must be at least one second");
        }
    }
}
