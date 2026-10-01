package backend.academy.linktracker.scrapper;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import backend.academy.linktracker.scrapper.repository.JdbcLinkRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.core.env.Environment;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import tools.jackson.databind.ObjectMapper;
import static org.assertj.core.api.Assertions.*;

@Testcontainers
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {"spring.liquibase.enabled=true", "app.scheduler.enabled=false"})
class LinkTrackerIT {
    @Container
    static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:16.4");

    @DynamicPropertySource
    static void database(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
    }

    @Autowired Environment environment;
    @Autowired ObjectMapper mapper;
    @Autowired JdbcLinkRepository repository;
    @Autowired TransactionTemplate transactions;

    @Test
    void servesFrontendAndPerformsPersistentCrud() throws Exception {
        assertThat(request("GET", "/", null).body()).contains("Ваши ссылки");
        String body = payload("https://github.com/test/crud", "Repository", List.of("java"));
        var created = request("POST", "/api/links", body);
        assertThat(created.statusCode()).isEqualTo(201);
        long id = mapper.readTree(created.body()).get("id").asLong();
        assertThat(request("POST", "/api/links", body).statusCode()).isEqualTo(409);
        var updated = request("PUT", "/api/links/" + id,
                payload("https://github.com/test/crud", "Updated", List.of("backend")));
        assertThat(updated.statusCode()).isEqualTo(200);
        assertThat(request("GET", "/api/links?tag=backend", null).body()).contains("Updated");
        assertThat(request("DELETE", "/api/links/" + id, null).statusCode()).isEqualTo(204);
        assertThat(request("GET", "/api/links/" + id, null).statusCode()).isEqualTo(404);
        assertThat(request("GET", "/api/links?page=-1", null).statusCode()).isEqualTo(400);
        assertThat(request("POST", "/api/links", payload("https://example.org", "Invalid", List.of())).statusCode())
                .isEqualTo(400);
    }

    @Test
    void deduplicatesNotificationsAndResetsHistoryOnUrlChange() throws Exception {
        var created = request("POST", "/api/links", payload("https://github.com/test/updates", "Updates", List.of()));
        long id = mapper.readTree(created.body()).get("id").asLong();
        OffsetDateTime time = OffsetDateTime.parse("2026-01-01T00:00:00Z");
        repository.recordUpdate(id, "First", time);
        repository.recordUpdate(id, "Duplicate", time);
        assertThat(repository.recentUpdates(id)).hasSize(1);
        request("PUT", "/api/links/" + id, payload("https://github.com/test/other", "Other", List.of()));
        assertThat(repository.recentUpdates(id)).isEmpty();
        request("DELETE", "/api/links/" + id, null);
    }

    @Test
    void concurrentWorkerSkipsLockedLink() throws Exception {
        var created = request("POST", "/api/links", payload("https://github.com/test/locking", "Lock", List.of()));
        long id = mapper.readTree(created.body()).get("id").asLong();
        CountDownLatch locked = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        try (var executor = Executors.newSingleThreadExecutor()) {
            var task = executor.submit(() -> transactions.executeWithoutResult(status -> {
                assertThat(repository.lockById(id)).isPresent();
                locked.countDown();
                try { assertThat(release.await(10, TimeUnit.SECONDS)).isTrue(); }
                catch (InterruptedException exception) { Thread.currentThread().interrupt(); throw new RuntimeException(exception); }
            }));
            try {
                assertThat(locked.await(5, TimeUnit.SECONDS)).isTrue();
                transactions.executeWithoutResult(status -> assertThat(repository.lockById(id)).isEmpty());
            } finally { release.countDown(); }
            task.get(5, TimeUnit.SECONDS);
        }
        request("DELETE", "/api/links/" + id, null);
    }

    private String payload(String url, String title, List<String> tags) {
        return mapper.writeValueAsString(Map.of("url", url, "title", title, "tags", tags, "enabled", true));
    }

    private HttpResponse<String> request(String method, String path, String body) throws Exception {
        String origin = "http://localhost:" + environment.getProperty("local.server.port");
        var request = HttpRequest.newBuilder(URI.create(origin + path)).header("Content-Type", "application/json")
                .method(method, body == null ? HttpRequest.BodyPublishers.noBody() : HttpRequest.BodyPublishers.ofString(body))
                .build();
        return HttpClient.newHttpClient().send(request, HttpResponse.BodyHandlers.ofString());
    }
}
