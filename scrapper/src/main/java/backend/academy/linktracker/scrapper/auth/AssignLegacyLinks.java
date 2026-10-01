package backend.academy.linktracker.scrapper.auth;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.core.env.Environment;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

@Component
@ConditionalOnProperty(name = "app.mode", havingValue = "assign-legacy")
public class AssignLegacyLinks implements ApplicationRunner {
    private static final Logger log = LoggerFactory.getLogger(AssignLegacyLinks.class);
    private final JdbcClient jdbc;
    private final TransactionTemplate transactions;
    private final Environment environment;

    public AssignLegacyLinks(JdbcClient jdbc, TransactionTemplate transactions, Environment environment) {
        this.jdbc = jdbc;
        this.transactions = transactions;
        this.environment = environment;
    }

    @Override
    public void run(ApplicationArguments args) {
        if (!"none".equals(environment.getProperty("spring.main.web-application-type"))
                || environment.getProperty("app.scheduler.enabled", Boolean.class, true)) {
            throw new IllegalStateException("assign-legacy requires non-web mode and SCHEDULER_ENABLED=false");
        }
        String email = AccountService.normalizeEmail(environment.getProperty("LEGACY_OWNER_EMAIL"));
        if (email.isBlank()) throw new IllegalStateException("Set LEGACY_OWNER_EMAIL to an existing account");
        Integer count = transactions.execute(status -> {
            Long userId = jdbc.sql("SELECT id FROM users WHERE email = :email").param("email", email)
                    .query(Long.class).optional().orElseThrow(() -> new IllegalStateException("Owner account not found"));
            long conflicts = jdbc.sql("""
                    SELECT COUNT(*) FROM links legacy JOIN links owned ON legacy.url = owned.url
                    WHERE legacy.owner_id IS NULL AND owned.owner_id = :owner
                    """).param("owner", userId).query(Long.class).single();
            if (conflicts != 0) {
                throw new IllegalStateException("Owner already tracks some legacy URLs; resolve duplicates before assignment");
            }
            return jdbc.sql("UPDATE links SET owner_id = :owner WHERE owner_id IS NULL")
                    .param("owner", userId).update();
        });
        log.info("Legacy links assigned: count={}", count);
    }
}
