package backend.academy.linktracker.scrapper.scheduler.checker;

import backend.academy.linktracker.scrapper.client.GitHubClient;
import java.net.URI;
import org.springframework.stereotype.Component;

@Component
public class GitHubUpdateChecker implements LinkUpdateChecker {
    private final GitHubClient client;

    public GitHubUpdateChecker(GitHubClient client) {
        this.client = client;
    }

    @Override
    public boolean supports(URI uri) {
        return "github.com".equalsIgnoreCase(uri.getHost());
    }

    @Override
    public CheckResult check(URI uri) {
        String[] path = uri.getPath().substring(1).split("/");
        var response = client.fetchRepository(path[0], path[1]);
        if (response == null || response.updatedAt() == null) {
            throw new IllegalStateException("GitHub returned no update timestamp");
        }
        String description = response.description() == null ? "Описание не указано." : response.description();
        if (description.length() > 200) description = description.substring(0, 200) + "…";
        return new CheckResult("Обновился репозиторий " + response.name() + ". " + description, response.updatedAt());
    }
}
