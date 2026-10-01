package backend.academy.linktracker.scrapper.client;

import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.List;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.service.annotation.GetExchange;
import org.springframework.web.service.annotation.HttpExchange;

@HttpExchange
public interface StackOverflowClient {
    @GetExchange("/questions/{id}?site=stackoverflow")
    StackOverflowResponse fetchQuestion(@PathVariable("id") Long id);

    record StackOverflowResponse(List<Item> items, Integer backoff) {
        // Stack Exchange sends Unix seconds, not an ISO OffsetDateTime string.
        public record Item(@JsonProperty("last_activity_date") Long lastActivityDate, String title) {}
    }
}
