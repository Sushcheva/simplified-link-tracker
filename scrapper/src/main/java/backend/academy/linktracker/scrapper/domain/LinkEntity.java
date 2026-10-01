package backend.academy.linktracker.scrapper.domain;

import java.net.URI;
import java.time.OffsetDateTime;
import java.util.List;

// Adapted from the original LinkEntity; tags now belong to the shared tracked link.
public record LinkEntity(
        long id, URI url, String title, List<String> tags, boolean enabled,
        OffsetDateTime createdAt, OffsetDateTime lastCheckTime,
        OffsetDateTime lastSeenAt, String lastError) {}
