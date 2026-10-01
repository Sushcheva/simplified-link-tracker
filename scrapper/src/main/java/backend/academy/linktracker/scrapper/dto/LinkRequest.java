package backend.academy.linktracker.scrapper.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.util.List;

public record LinkRequest(
        @NotBlank @Size(max = 2048) String url,
        @NotBlank @Size(max = 120) String title,
        @NotNull @Size(max = 10) List<@NotBlank @Size(max = 32) String> tags,
        @NotNull Boolean enabled) {}
