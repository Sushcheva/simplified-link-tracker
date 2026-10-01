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
    public LinkPage listLinks(String search, String tag, int page, int size) {
        return repository.findAll(search.trim(), tag.trim(), page, size);
    }

    public LinkEntity getLink(long id) {
        return repository.findById(id).orElseThrow(this::notFound);
    }

    @Transactional
    public LinkEntity addLink(LinkRequest request) {
        return repository.addLink(normalizer.normalize(request.url()), request.title().trim(),
                normalizeTags(request.tags()), request.enabled());
    }

    @Transactional
    public LinkEntity updateLink(long id, LinkRequest request) {
        var url = normalizer.normalize(request.url());
        // Serialize a user's edit with background checks and other edits of the same link.
        var previous = repository.lockById(id).orElseThrow(() -> repository.findById(id).isPresent()
                ? new ApiException(HttpStatus.CONFLICT, "Ссылка сейчас проверяется. Повторите сохранение позже.")
                : notFound());
        if (!previous.url().equals(url)) repository.clearUpdates(id);
        return repository.updateLink(id, url, request.title().trim(), normalizeTags(request.tags()), request.enabled())
                .orElseThrow(this::notFound);
    }

    @Transactional
    public void removeLink(long id) {
        if (repository.deleteLink(id) == 0) throw notFound();
    }

    public List<LinkUpdate> recentUpdates(Long linkId) {
        if (linkId != null) getLink(linkId);
        return repository.recentUpdates(linkId);
    }

    private List<String> normalizeTags(List<String> tags) {
        return tags.stream().map(String::trim).filter(tag -> !tag.isEmpty()).distinct().toList();
    }

    private ApiException notFound() {
        return new ApiException(HttpStatus.NOT_FOUND, "Ссылка не найдена.");
    }
}
