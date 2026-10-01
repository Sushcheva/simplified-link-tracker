package backend.academy.linktracker.scrapper;

import java.net.URI;
import java.net.CookieManager;
import java.net.CookiePolicy;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.AfterAll;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.crypto.password.PasswordEncoder;
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
import org.testcontainers.postgresql.PostgreSQLContainer;
import tools.jackson.databind.ObjectMapper;
import static org.assertj.core.api.Assertions.*;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {"spring.liquibase.enabled=true", "app.scheduler.enabled=false"})
class LinkTrackerIT {
    static PostgreSQLContainer postgres;

    @DynamicPropertySource
    static void database(DynamicPropertyRegistry registry) {
        String url = System.getenv("TEST_DATABASE_URL");
        if (url == null) {
            postgres = new PostgreSQLContainer("postgres:16.4");
            postgres.start();
            registry.add("spring.datasource.url", postgres::getJdbcUrl);
            registry.add("spring.datasource.username", postgres::getUsername);
            registry.add("spring.datasource.password", postgres::getPassword);
        } else {
            registry.add("spring.datasource.url", () -> url);
            registry.add("spring.datasource.username", () -> System.getenv("TEST_DATABASE_USER"));
            registry.add("spring.datasource.password", () -> System.getenv("TEST_DATABASE_PASSWORD"));
        }
    }

    @AfterAll
    static void stopDatabase() { if (postgres != null) postgres.stop(); }

    @Autowired Environment environment;
    @Autowired ObjectMapper mapper;
    @Autowired JdbcLinkRepository repository;
    @Autowired TransactionTemplate transactions;
    @Autowired JdbcClient jdbc;
    @Autowired PasswordEncoder encoder;
    private Browser browser;
    private long ownerId;
    private String email;
    private static final String PASSWORD = "test-password-2026";

    @BeforeEach
    void signIn() throws Exception {
        browser = new Browser();
        email = UUID.randomUUID() + "@example.test";
        var registered = browser.send("POST", "/api/auth/register", credentials(email, PASSWORD), true);
        assertThat(registered.statusCode()).isEqualTo(201);
        ownerId = mapper.readTree(registered.body()).get("id").asLong();
        assertThat(browser.login(email, PASSWORD).statusCode()).isEqualTo(200);
    }

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
        assertThat(repository.recentUpdates(ownerId, id)).hasSize(1);
        request("PUT", "/api/links/" + id, payload("https://github.com/test/other", "Other", List.of()));
        assertThat(repository.recentUpdates(ownerId, id)).isEmpty();
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
                assertThat(repository.lockById(ownerId, id)).isPresent();
                locked.countDown();
                try { assertThat(release.await(10, TimeUnit.SECONDS)).isTrue(); }
                catch (InterruptedException exception) { Thread.currentThread().interrupt(); throw new RuntimeException(exception); }
            }));
            try {
                assertThat(locked.await(5, TimeUnit.SECONDS)).isTrue();
                transactions.executeWithoutResult(status -> assertThat(repository.lockById(ownerId, id)).isEmpty());
            } finally { release.countDown(); }
            task.get(5, TimeUnit.SECONDS);
        }
        request("DELETE", "/api/links/" + id, null);
    }

    @Test
    void requiresAuthenticationAndCsrfForMutations() throws Exception {
        var anonymous = new Browser();
        for (String path : List.of("/api/auth/me", "/api/links", "/api/links/1", "/api/updates")) {
            assertThat(anonymous.send("GET", path, null, false).statusCode()).isEqualTo(401);
        }
        assertThat(anonymous.send("POST", "/api/links", payload("https://github.com/test/secret", "Secret", List.of()), true)
                .statusCode()).isEqualTo(401);
        assertThat(browser.send("POST", "/api/links", "{}", false).statusCode()).isEqualTo(403);
        assertThat(anonymous.send("POST", "/api/auth/register", credentials("new@example.test", PASSWORD), false)
                .statusCode()).isEqualTo(403);
        assertThat(anonymous.send("POST", "/api/auth/login", "email=x&password=y", false).statusCode()).isEqualTo(403);
        assertThat(browser.send("POST", "/api/auth/logout", null, false).statusCode()).isEqualTo(403);
        assertThat(request("GET", "/api/auth/me", null).statusCode()).isEqualTo(200);
    }

    @Test
    void isolatesAllLinkOperationsAndHistoryByOwner() throws Exception {
        String body = payload("https://github.com/test/private", "Private", List.of("private"));
        var created = request("POST", "/api/links", body);
        long id = mapper.readTree(created.body()).get("id").asLong();
        repository.recordUpdate(id, "Private event", OffsetDateTime.parse("2026-01-01T00:00:00Z"));
        var other = new Browser();
        String otherEmail = UUID.randomUUID() + "@example.test";
        assertThat(other.send("POST", "/api/auth/register", credentials(otherEmail, PASSWORD), true).statusCode()).isEqualTo(201);
        assertThat(other.login(otherEmail, PASSWORD).statusCode()).isEqualTo(200);
        assertThat(mapper.readTree(other.send("GET", "/api/links?tag=private&search=Private", null, true).body()).get("total").asInt()).isZero();
        assertThat(other.send("GET", "/api/updates", null, true).body()).isEqualTo("[]");
        for (String path : List.of("/api/links/" + id, "/api/updates?linkId=" + id)) {
            assertThat(other.send("GET", path, null, true).statusCode()).isEqualTo(404);
        }
        assertThat(other.send("PUT", "/api/links/" + id, body, true).statusCode()).isEqualTo(404);
        assertThat(other.send("POST", "/api/links/" + id + "/check", null, true).statusCode()).isEqualTo(404);
        assertThat(other.send("DELETE", "/api/links/" + id, null, true).statusCode()).isEqualTo(404);
        assertThat(request("GET", "/api/links/" + id, null).statusCode()).isEqualTo(200);
        assertThat(request("GET", "/api/updates?linkId=" + id, null).body()).contains("Private event");
        assertThat(other.send("POST", "/api/links", body, true).statusCode()).isEqualTo(201);
        assertThat(other.send("POST", "/api/links", body, true).statusCode()).isEqualTo(409);
    }

    @Test
    void normalizesEmailHashesPasswordsAndValidatesRegistration() throws Exception {
        var anonymous = new Browser();
        assertThat(anonymous.send("POST", "/api/auth/register", credentials(email.toUpperCase(), PASSWORD), true)
                .statusCode()).isEqualTo(409);
        assertThat(anonymous.login("  " + email.toUpperCase() + "  ", PASSWORD).statusCode()).isEqualTo(200);
        String hash = jdbc.sql("SELECT password_hash FROM users WHERE id = :id").param("id", ownerId).query(String.class).single();
        assertThat(hash).startsWith("$2a$12$").isNotEqualTo(PASSWORD);
        assertThat(encoder.matches(PASSWORD, hash)).isTrue();
        assertThat(request("GET", "/api/auth/me", null).body()).doesNotContain("password", hash, PASSWORD);
        for (String password : List.of("short", "x".repeat(65), "я".repeat(37), " ".repeat(12))) {
            assertThat(anonymous.send("POST", "/api/auth/register", credentials(UUID.randomUUID() + "@example.test", password), true)
                    .statusCode()).isEqualTo(400);
        }
        assertThat(anonymous.send("POST", "/api/auth/register", credentials("invalid-email", PASSWORD), true).statusCode()).isEqualTo(400);
    }

    @Test
    void rejectsWrongCredentialsWithoutRevealingAccountExistence() throws Exception {
        var anonymous = new Browser();
        var wrong = anonymous.login(email, "wrong-password");
        var unknown = anonymous.login("missing@example.test", "wrong-password");
        assertThat(wrong.statusCode()).isEqualTo(401);
        assertThat(unknown.statusCode()).isEqualTo(401);
        assertThat(wrong.body()).isEqualTo(unknown.body());
        assertThat(anonymous.login(email, "x".repeat(100)).statusCode()).isEqualTo(401);
    }

    @Test
    void rotatesSessionAtLoginAndRevokesItOnLogout() throws Exception {
        var another = new Browser();
        another.refreshCsrf();
        String before = another.cookie();
        var login = another.login(email, PASSWORD);
        String authenticated = another.cookie();
        assertThat(authenticated).isNotEqualTo(before);
        assertThat(login.headers().firstValue("set-cookie").orElseThrow()).contains("HttpOnly", "SameSite=Lax");
        assertThat(replayCookie(before).statusCode()).isEqualTo(401);
        assertThat(replayCookie(authenticated).statusCode()).isEqualTo(200);
        assertThat(another.send("POST", "/api/auth/logout", null, true).statusCode()).isEqualTo(204);
        assertThat(replayCookie(authenticated).statusCode()).isEqualTo(401);
        assertThat(another.send("GET", "/api/auth/me", null, true).statusCode()).isEqualTo(401);
        // Logging out one browser leaves an independent login session intact.
        assertThat(request("GET", "/api/auth/me", null).statusCode()).isEqualTo(200);
    }

    @Test
    void expiresSessionsUsingTheSharedDatabase() throws Exception {
        jdbc.sql("UPDATE spring_session SET last_access_time = 0, expiry_time = 0 WHERE principal_name = :email")
                .param("email", email).update();
        assertThat(request("GET", "/api/auth/me", null).statusCode()).isEqualTo(401);
    }

    @Test
    void legacyLinksRemainPrivateAndAreNotScheduled() throws Exception {
        long id = jdbc.sql("INSERT INTO links (url, title, enabled) VALUES ('https://github.com/test/legacy', 'Legacy', true) RETURNING id")
                .query(Long.class).single();
        try {
            assertThat(request("GET", "/api/links/" + id, null).statusCode()).isEqualTo(404);
            assertThat(request("GET", "/api/updates?linkId=" + id, null).statusCode()).isEqualTo(404);
            assertThat(request("GET", "/api/links", null).body()).doesNotContain("Legacy");
            transactions.executeWithoutResult(status -> {
                // Make only the unassigned link due, then verify the scheduler ignores it.
                jdbc.sql("UPDATE links SET next_check_at = CURRENT_TIMESTAMP + INTERVAL '1 day' WHERE owner_id IS NOT NULL").update();
                assertThat(repository.lockNextDue()).isEmpty();
            });
        } finally { jdbc.sql("DELETE FROM links WHERE id = :id").param("id", id).update(); }
    }

    private String payload(String url, String title, List<String> tags) {
        return mapper.writeValueAsString(Map.of("url", url, "title", title, "tags", tags, "enabled", true));
    }

    private String credentials(String email, String password) {
        return mapper.writeValueAsString(Map.of("email", email, "password", password));
    }

    private String origin() { return "http://localhost:" + environment.getProperty("local.server.port"); }

    private HttpResponse<String> request(String method, String path, String body) throws Exception {
        return browser.send(method, path, body, true);
    }

    private HttpResponse<String> replayCookie(String cookie) throws Exception {
        return HttpClient.newHttpClient().send(HttpRequest.newBuilder(URI.create(origin() + "/api/auth/me"))
                .header("Cookie", cookie).GET().build(), HttpResponse.BodyHandlers.ofString());
    }

    private class Browser {
        final CookieManager cookies = new CookieManager(null, CookiePolicy.ACCEPT_ALL);
        final HttpClient client = HttpClient.newBuilder().cookieHandler(cookies).build();
        String csrf;

        String cookie() {
            return cookies.getCookieStore().getCookies().stream()
                    .filter(c -> c.getName().equals("LINK_TRACKER_SESSION"))
                    .map(c -> c.getName() + "=" + c.getValue()).findFirst().orElseThrow();
        }

        void refreshCsrf() throws Exception {
            var response = send("GET", "/api/auth/csrf", null, false);
            assertThat(response.statusCode()).isEqualTo(200);
            csrf = mapper.readTree(response.body()).get("token").asText();
        }

        HttpResponse<String> login(String email, String password) throws Exception {
            var response = send("POST", "/api/auth/login", "email=" + URLEncoder.encode(email, StandardCharsets.UTF_8)
                    + "&password=" + URLEncoder.encode(password, StandardCharsets.UTF_8), true);
            refreshCsrf();
            return response;
        }

        HttpResponse<String> send(String method, String path, String body, boolean withCsrf) throws Exception {
            boolean unsafe = !method.equals("GET");
            if (unsafe && withCsrf && csrf == null) refreshCsrf();
            var builder = HttpRequest.newBuilder(URI.create(origin() + path))
                    .header("Content-Type", path.equals("/api/auth/login") ? "application/x-www-form-urlencoded" : "application/json")
                    .method(method, body == null ? HttpRequest.BodyPublishers.noBody() : HttpRequest.BodyPublishers.ofString(body));
            if (unsafe && withCsrf) builder.header("X-CSRF-TOKEN", csrf);
            return client.send(builder.build(), HttpResponse.BodyHandlers.ofString());
        }
    }
}
