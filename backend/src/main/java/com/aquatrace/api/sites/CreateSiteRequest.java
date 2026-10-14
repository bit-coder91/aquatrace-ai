package com.aquatrace.api.sites;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record CreateSiteRequest(
        @NotBlank @Size(max = 120) String name,
        @NotBlank @Size(max = 80) String timezone) {
}
