package backend.academy.linktracker.scrapper.scheduler.checker;

import backend.academy.linktracker.scrapper.client.StackOverflowClient;
import java.net.URI;
import java.time.Instant;
import java.time.ZoneOffset;
import org.springframework.stereotype.Component;

@Component
public class StackOverflowUpdateChecker implements LinkUpdateChecker {
    private final StackOverflowClient client;

    public StackOverflowUpdateChecker(StackOverflowClient client) {
        this.client = client;
    }

    @Override
    public boolean supports(URI uri) {
        return "stackoverflow.com".equalsIgnoreCase(uri.getHost());
    }

    @Override
    public CheckResult check(URI uri) {
        long id = Long.parseLong(uri.getPath().split("/")[2]);
        var response = client.fetchQuestion(id);
        if (response == null || response.items() == null || response.items().isEmpty()
                || response.items().getFirst().lastActivityDate() == null) {
            throw new IllegalStateException("Stack Overflow question unavailable");
        }
        var question = response.items().getFirst();
        return new CheckResult("Новая активность в вопросе: " + question.title(),
                Instant.ofEpochSecond(question.lastActivityDate()).atOffset(ZoneOffset.UTC));
    }
}
