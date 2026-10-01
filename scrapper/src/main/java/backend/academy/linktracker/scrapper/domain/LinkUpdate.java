package backend.academy.linktracker.scrapper.domain;

import java.net.URI;
import java.time.OffsetDateTime;

public record LinkUpdate(long id, long linkId, URI url, String title, String description,
                         OffsetDateTime remoteUpdatedAt, OffsetDateTime detectedAt) {}
