package backend.academy.linktracker.scrapper.properties;

import jakarta.validation.constraints.NotBlank;
import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties("app.external-api")
public record ExternalApiProperties(@NotBlank String githubUrl, @NotBlank String stackoverflowUrl,
                                    String githubToken, Duration connectTimeout, Duration readTimeout) {}
