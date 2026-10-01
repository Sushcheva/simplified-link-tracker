package backend.academy.linktracker.scrapper.scheduler.checker;

import java.time.OffsetDateTime;

public record CheckResult(String message, OffsetDateTime updatedAt) {}
