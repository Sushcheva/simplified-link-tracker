package backend.academy.linktracker.scrapper.client;

import com.fasterxml.jackson.annotation.JsonProperty;
import java.time.OffsetDateTime;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.service.annotation.GetExchange;
import org.springframework.web.service.annotation.HttpExchange;

// Retained from the original project's declarative HTTP client.
@HttpExchange
public interface GitHubClient {
    @GetExchange("/repos/{owner}/{repo}")
    GitHubResponse fetchRepository(@PathVariable("owner") String owner, @PathVariable("repo") String repo);

    record GitHubResponse(String name, @JsonProperty("updated_at") OffsetDateTime updatedAt,
                          String description, Owner owner) {
        public record Owner(String login) {}
    }
}
