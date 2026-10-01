package backend.academy.linktracker.scrapper.repository;

import backend.academy.linktracker.scrapper.domain.LinkEntity;
import backend.academy.linktracker.scrapper.domain.LinkUpdate;
import backend.academy.linktracker.scrapper.dto.LinkPage;
import java.net.URI;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;

/** Based on the original JdbcLinkRepository: JdbcClient, parameterized SQL and JSONB tags. */
@Repository
public class JdbcLinkRepository {
    private static final String FILTER = """
            WHERE owner_id = :ownerId AND (:search = '' OR title ILIKE '%' || :search || '%' OR url ILIKE '%' || :search || '%')
              AND (:tag = '' OR tags @> CAST(:tagJson AS jsonb))
            """;
    private final JdbcClient jdbc;
    private final ObjectMapper mapper;

    public JdbcLinkRepository(JdbcClient jdbc, ObjectMapper mapper) {
        this.jdbc = jdbc;
        this.mapper = mapper;
    }

    public LinkPage findAll(long ownerId, String search, String tag, int page, int size) {
        String tagJson = mapper.writeValueAsString(List.of(tag));
        long total = jdbc.sql("SELECT COUNT(*) FROM links " + FILTER)
                .param("ownerId", ownerId).param("search", search).param("tag", tag).param("tagJson", tagJson)
                .query(Long.class).single();
        List<LinkEntity> links = jdbc.sql("SELECT * FROM links " + FILTER + " ORDER BY id DESC LIMIT :size OFFSET :offset")
                .param("ownerId", ownerId).param("search", search).param("tag", tag).param("tagJson", tagJson)
                .param("size", size).param("offset", (long) page * size).query(this::mapLink).list();
        return new LinkPage(links, total, page, size);
    }

    public Optional<LinkEntity> findById(long ownerId, long id) {
        return jdbc.sql("SELECT * FROM links WHERE id = :id AND owner_id = :ownerId").param("ownerId", ownerId).param("id", id).query(this::mapLink).optional();
    }

    public LinkEntity addLink(long ownerId, URI url, String title, List<String> tags, boolean enabled) {
        return jdbc.sql("""
                INSERT INTO links (owner_id, url, title, tags, enabled)
                VALUES (:ownerId, :url, :title, CAST(:tags AS jsonb), :enabled) RETURNING *
                """).param("ownerId", ownerId).param("url", url.toString()).param("title", title)
                .param("tags", mapper.writeValueAsString(tags)).param("enabled", enabled)
                .query(this::mapLink).single();
    }

    public Optional<LinkEntity> updateLink(long ownerId, long id, URI url, String title, List<String> tags, boolean enabled) {
        // Changing the resource establishes a fresh monitoring baseline.
        return jdbc.sql("""
                UPDATE links SET title = :title, tags = CAST(:tags AS jsonb), enabled = :enabled,
                    last_seen_at = CASE WHEN url <> :url THEN NULL ELSE last_seen_at END,
                    last_check_time = CASE WHEN url <> :url THEN NULL ELSE last_check_time END,
                    last_error = CASE WHEN url <> :url THEN NULL ELSE last_error END,
                    next_check_at = CASE WHEN url <> :url OR (NOT enabled AND :enabled)
                        THEN CURRENT_TIMESTAMP ELSE next_check_at END,
                    url = :url
                WHERE id = :id AND owner_id = :ownerId RETURNING *
                """).param("ownerId", ownerId).param("id", id).param("url", url.toString()).param("title", title)
                .param("tags", mapper.writeValueAsString(tags)).param("enabled", enabled)
                .query(this::mapLink).optional();
    }

    public Optional<LinkEntity> lockById(long ownerId, long id) {
        return jdbc.sql("SELECT * FROM links WHERE id = :id AND owner_id = :ownerId FOR UPDATE SKIP LOCKED")
                .param("ownerId", ownerId).param("id", id).query(this::mapLink).optional();
    }

    public Optional<LinkEntity> lockNextDue() {
        return jdbc.sql("""
                SELECT * FROM links WHERE owner_id IS NOT NULL AND enabled AND next_check_at <= CURRENT_TIMESTAMP
                ORDER BY next_check_at, id LIMIT 1 FOR UPDATE SKIP LOCKED
                """).query(this::mapLink).optional();
    }

    public int deleteLink(long ownerId, long id) {
        return jdbc.sql("DELETE FROM links WHERE id = :id AND owner_id = :ownerId")
                .param("ownerId", ownerId).param("id", id).update();
    }

    public void clearUpdates(long ownerId, long id) {
        jdbc.sql("DELETE FROM link_updates WHERE link_id = :id AND link_id IN (SELECT id FROM links WHERE owner_id = :ownerId)")
                .param("ownerId", ownerId).param("id", id).update();
    }

    public void recordUpdate(long id, String description, OffsetDateTime updatedAt) {
        jdbc.sql("""
                INSERT INTO link_updates (link_id, description, remote_updated_at)
                VALUES (:id, :description, :updatedAt) ON CONFLICT (link_id, remote_updated_at) DO NOTHING
                """).param("id", id).param("description", description).param("updatedAt", updatedAt).update();
    }

    public void finishCheck(long id, OffsetDateTime lastSeenAt, long intervalSeconds, String error) {
        jdbc.sql("""
                UPDATE links SET last_check_time = CURRENT_TIMESTAMP, last_seen_at = :lastSeenAt,
                    next_check_at = CURRENT_TIMESTAMP + :seconds * INTERVAL '1 second', last_error = :error
                WHERE id = :id
                """).param("id", id).param("lastSeenAt", lastSeenAt)
                .param("seconds", intervalSeconds).param("error", error).update();
    }

    public List<LinkUpdate> recentUpdates(long ownerId, Long linkId) {
        String filter = " WHERE l.owner_id = :ownerId" + (linkId == null ? "" : " AND u.link_id = :linkId");
        var query = jdbc.sql("""
                SELECT u.*, l.url, l.title FROM link_updates u JOIN links l ON l.id = u.link_id
                """ + filter + " ORDER BY u.detected_at DESC, u.id DESC LIMIT 20").param("ownerId", ownerId);
        if (linkId != null) query = query.param("linkId", linkId);
        return query.query((rs, row) -> new LinkUpdate(rs.getLong("id"), rs.getLong("link_id"),
                URI.create(rs.getString("url")), rs.getString("title"), rs.getString("description"),
                rs.getObject("remote_updated_at", OffsetDateTime.class),
                rs.getObject("detected_at", OffsetDateTime.class))).list();
    }

    private LinkEntity mapLink(ResultSet rs, int row) throws SQLException {
        List<String> tags = mapper.readValue(rs.getString("tags"), new TypeReference<List<String>>() {});
        return new LinkEntity(rs.getLong("id"), URI.create(rs.getString("url")), rs.getString("title"),
                tags, rs.getBoolean("enabled"), rs.getObject("created_at", OffsetDateTime.class),
                rs.getObject("last_check_time", OffsetDateTime.class),
                rs.getObject("last_seen_at", OffsetDateTime.class), rs.getString("last_error"));
    }
}
