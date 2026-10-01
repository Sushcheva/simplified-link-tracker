package backend.academy.linktracker.scrapper;

import backend.academy.linktracker.scrapper.domain.LinkEntity;
import backend.academy.linktracker.scrapper.properties.SchedulerProperties;
import backend.academy.linktracker.scrapper.repository.JdbcLinkRepository;
import backend.academy.linktracker.scrapper.scheduler.checker.CheckResult;
import backend.academy.linktracker.scrapper.scheduler.checker.LinkUpdateChecker;
import backend.academy.linktracker.scrapper.service.LinkMonitorService;
import java.net.URI;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataAccessResourceFailureException;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class LinkMonitorServiceTest {
    private final JdbcLinkRepository repository = mock(JdbcLinkRepository.class);
    private final LinkUpdateChecker checker = mock(LinkUpdateChecker.class);
    private final URI url = URI.create("https://github.com/a/b");
    private final OffsetDateTime baseline = OffsetDateTime.parse("2026-01-01T00:00:00Z");
    private final LinkMonitorService monitor = new LinkMonitorService(repository, List.of(checker),
            new SchedulerProperties(true, Duration.ofMinutes(1), 20));

    @BeforeEach
    void setup() {
        when(checker.supports(url)).thenReturn(true);
    }

    @Test
    void firstSuccessfulCheckEstablishesBaselineWithoutNotification() {
        when(repository.lockNextDue()).thenReturn(Optional.of(link(null)));
        when(checker.check(url)).thenReturn(new CheckResult("baseline", baseline));
        assertThat(monitor.checkNext()).isTrue();
        verify(repository, never()).recordUpdate(anyLong(), anyString(), any());
        verify(repository).finishCheck(1, baseline, 60, null);
    }

    @Test
    void laterVersionCreatesAnUpdate() {
        when(repository.lockNextDue()).thenReturn(Optional.of(link(baseline)));
        when(checker.check(url)).thenReturn(new CheckResult("changed", baseline.plusHours(1)));
        monitor.checkNext();
        verify(repository).recordUpdate(1, "changed", baseline.plusHours(1));
    }

    @Test
    void olderApiResponseDoesNotMoveCheckpointBackwards() {
        when(repository.lockNextDue()).thenReturn(Optional.of(link(baseline)));
        when(checker.check(url)).thenReturn(new CheckResult("old", baseline.minusHours(1)));
        monitor.checkNext();
        verify(repository, never()).recordUpdate(anyLong(), anyString(), any());
        verify(repository).finishCheck(1, baseline, 60, null);
    }

    @Test
    void externalFailureKeepsCheckpointAndDelaysRetry() {
        when(repository.lockNextDue()).thenReturn(Optional.of(link(baseline)));
        when(checker.check(url)).thenThrow(new IllegalStateException("offline"));
        monitor.checkNext();
        verify(repository).finishCheck(eq(1L), eq(baseline), eq(300L), contains("Не удалось"));
    }

    @Test
    void databaseFailureMustEscapeToRollbackTransaction() {
        when(repository.lockNextDue()).thenReturn(Optional.of(link(baseline)));
        when(checker.check(url)).thenReturn(new CheckResult("new", baseline.plusHours(1)));
        doThrow(new DataAccessResourceFailureException("db unavailable"))
                .when(repository).recordUpdate(anyLong(), anyString(), any());
        assertThatThrownBy(monitor::checkNext).isInstanceOf(DataAccessResourceFailureException.class);
        verify(repository, never()).finishCheck(anyLong(), any(), anyLong(), any());
    }

    private LinkEntity link(OffsetDateTime lastSeen) {
        return new LinkEntity(1, url, "Repo", List.of("java"), true, baseline, null, lastSeen, null);
    }
}
