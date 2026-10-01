package backend.academy.linktracker.scrapper.service;

import backend.academy.linktracker.scrapper.domain.LinkEntity;
import backend.academy.linktracker.scrapper.exception.ApiException;
import backend.academy.linktracker.scrapper.properties.SchedulerProperties;
import backend.academy.linktracker.scrapper.repository.JdbcLinkRepository;
import backend.academy.linktracker.scrapper.scheduler.checker.LinkUpdateChecker;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.client.RestClientResponseException;

@Service
public class LinkMonitorService {
    private static final Logger log = LoggerFactory.getLogger(LinkMonitorService.class);
    private final JdbcLinkRepository repository;
    private final List<LinkUpdateChecker> checkers;
    private final SchedulerProperties properties;

    public LinkMonitorService(JdbcLinkRepository repository, List<LinkUpdateChecker> checkers,
                              SchedulerProperties properties) {
        this.repository = repository;
        this.checkers = checkers;
        this.properties = properties;
    }

    @Transactional
    public boolean checkNext() {
        var link = repository.lockNextDue();
        if (link.isEmpty()) return false;
        check(link.get());
        return true;
    }

    @Transactional
    public LinkEntity checkNow(long id) {
        var link = repository.lockById(id).orElseThrow(() -> repository.findById(id).isPresent()
                ? new ApiException(HttpStatus.CONFLICT, "Ссылка уже проверяется. Попробуйте позже.")
                : new ApiException(HttpStatus.NOT_FOUND, "Ссылка не найдена."));
        check(link);
        return repository.findById(id).orElseThrow();
    }

    private void check(LinkEntity link) {
        var lastSeenAt = link.lastSeenAt();
        String error = null;
        backend.academy.linktracker.scrapper.scheduler.checker.CheckResult result = null;
        try {
            result = checkers.stream().filter(checker -> checker.supports(link.url())).findFirst()
                    .orElseThrow(() -> new IllegalStateException("Unsupported link")).check(link.url());
        } catch (RestClientResponseException exception) {
            error = "Внешний сервис вернул HTTP " + exception.getStatusCode().value()
                    + ". Проверьте доступность ресурса или повторите позже.";
        } catch (RuntimeException exception) {
            error = "Не удалось проверить ресурс. Повторная попытка будет выполнена позже.";
            log.warn("External check failed: linkId={}, type={}", link.id(), exception.getClass().getSimpleName());
        }
        // Database failures are not swallowed: the entire check must roll back on a failed write.
        if (result != null) {
            if (lastSeenAt != null && result.updatedAt().isAfter(lastSeenAt)) {
                repository.recordUpdate(link.id(), result.message(), result.updatedAt());
            }
            if (lastSeenAt == null || result.updatedAt().isAfter(lastSeenAt)) lastSeenAt = result.updatedAt();
        }
        long delay = error == null ? properties.interval().toSeconds() : Math.max(300, properties.interval().toSeconds());
        repository.finishCheck(link.id(), lastSeenAt, delay, error);
        log.info("Link checked: linkId={}, successful={}", link.id(), error == null);
    }
}
