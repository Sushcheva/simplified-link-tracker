package backend.academy.linktracker.scrapper.controller;

import backend.academy.linktracker.scrapper.domain.LinkEntity;
import backend.academy.linktracker.scrapper.domain.LinkUpdate;
import backend.academy.linktracker.scrapper.dto.LinkPage;
import backend.academy.linktracker.scrapper.dto.LinkRequest;
import backend.academy.linktracker.scrapper.service.LinkMonitorService;
import backend.academy.linktracker.scrapper.service.LinkService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Size;
import java.net.URI;
import java.util.List;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api")
public class ScrapperController {
    private final LinkService service;
    private final LinkMonitorService monitor;

    public ScrapperController(LinkService service, LinkMonitorService monitor) {
        this.service = service;
        this.monitor = monitor;
    }

    @GetMapping("/links")
    public LinkPage list(@RequestParam(defaultValue = "") @Size(max = 200) String search,
                         @RequestParam(defaultValue = "") @Size(max = 32) String tag,
                         @RequestParam(defaultValue = "0") @Min(0) int page,
                         @RequestParam(defaultValue = "20") @Min(1) @Max(100) int size) {
        return service.listLinks(search, tag, page, size);
    }

    @GetMapping("/links/{id}")
    public LinkEntity get(@PathVariable @Min(1) long id) {
        return service.getLink(id);
    }

    @PostMapping("/links")
    public ResponseEntity<LinkEntity> create(@Valid @RequestBody LinkRequest request) {
        var link = service.addLink(request);
        return ResponseEntity.created(URI.create("/api/links/" + link.id())).body(link);
    }

    @PutMapping("/links/{id}")
    public LinkEntity update(@PathVariable @Min(1) long id, @Valid @RequestBody LinkRequest request) {
        return service.updateLink(id, request);
    }

    @DeleteMapping("/links/{id}")
    public ResponseEntity<Void> delete(@PathVariable @Min(1) long id) {
        service.removeLink(id);
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/links/{id}/check")
    public LinkEntity check(@PathVariable @Min(1) long id) {
        return monitor.checkNow(id);
    }

    @GetMapping("/updates")
    public List<LinkUpdate> updates(@RequestParam(required = false) @Min(1) Long linkId) {
        return service.recentUpdates(linkId);
    }
}
