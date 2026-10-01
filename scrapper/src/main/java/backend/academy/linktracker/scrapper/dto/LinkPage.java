package backend.academy.linktracker.scrapper.dto;

import backend.academy.linktracker.scrapper.domain.LinkEntity;
import java.util.List;

public record LinkPage(List<LinkEntity> links, long total, int page, int size) {}
