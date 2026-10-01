package backend.academy.linktracker.scrapper.scheduler.checker;

import java.net.URI;

public interface LinkUpdateChecker {
    boolean supports(URI uri);
    CheckResult check(URI uri);
}
