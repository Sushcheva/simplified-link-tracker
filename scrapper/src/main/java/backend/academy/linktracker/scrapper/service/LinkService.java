package backend.academy.linktracker.scrapper.service;

import backend.academy.linktracker.scrapper.domain.LinkEntity;
import backend.academy.linktracker.scrapper.domain.LinkUpdate;
import backend.academy.linktracker.scrapper.dto.LinkPage;
import backend.academy.linktracker.scrapper.dto.LinkRequest;
import backend.academy.linktracker.scrapper.exception.ApiException;
import backend.academy.linktracker.scrapper.repository.JdbcLinkRepository;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

@Service
@Transactional(readOnly = true)
public class LinkService {
    private final JdbcLinkRepository repository;
    private final LinkUrlNormalizer normalizer;

    public LinkService(JdbcLinkRepository repository, LinkUrlNormalizer normalizer) {
        this.repository = repository;
        this.normalizer = normalizer;
    }

    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public LinkPage listLinks(long ownerId, String search, String tag, int page, int size) {
        return repository.findAll(ownerId, search.trim(), tag.trim(), page, size);
    }

    public LinkEntity getLink(long ownerId, long id) {
        return repository.findById(ownerId, id).orElseThrow(this::notFound);
    }

    @Transactional
    public LinkEntity addLink(long ownerId, LinkRequest request) {
        return repository.addLink(ownerId, normalizer.normalize(request.url()), request.title().trim(),
                normalizeTags(request.tags()), request.enabled());
    }

    @Transactional
    public LinkEntity updateLink(long ownerId, long id, LinkRequest request) {
        var url = normalizer.normalize(request.url());
        // Serialize a user's edit with background checks and other edits of the same link.
        var previous = repository.lockById(ownerId, id).orElseThrow(() -> repository.findById(ownerId, id).isPresent()
                ? new ApiException(HttpStatus.CONFLICT, "Ссылка сейчас проверяется. Повторите сохранение позже.")
                : notFound());
        if (!previous.url().equals(url)) repository.clearUpdates(ownerId, id);
        return repository.updateLink(ownerId, id, url, request.title().trim(), normalizeTags(request.tags()), request.enabled())
                .orElseThrow(this::notFound);
    }

    @Transactional
    public void removeLink(long ownerId, long id) {
        if (repository.deleteLink(ownerId, id) == 0) throw notFound();
    }

    public List<LinkUpdate> recentUpdates(long ownerId, Long linkId) {
        if (linkId != null) getLink(ownerId, linkId);
        return repository.recentUpdates(ownerId, linkId);
    }

    private List<String> normalizeTags(List<String> tags) {
        return tags.stream().map(String::trim).filter(tag -> !tag.isEmpty()).distinct().toList();
    }

    private ApiException notFound() {
        return new ApiException(HttpStatus.NOT_FOUND, "Ссылка не найдена.");
    }
}
