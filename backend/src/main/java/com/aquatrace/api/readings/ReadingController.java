package com.aquatrace.api.readings;

import com.aquatrace.api.security.FirebasePrincipal;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Pattern;
import java.time.Instant;
import java.util.List;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@Validated
@RestController
@RequestMapping("/api/v1/sites/{siteId}/meters/{meterId}/readings")
public class ReadingController {
    private final ReadingService readingService;

    public ReadingController(ReadingService readingService) { this.readingService = readingService; }

    @PostMapping
    public ReadingSummary create(@PathVariable @Pattern(regexp = "^[A-Za-z0-9_-]{1,128}$") String siteId,
                                 @PathVariable @Pattern(regexp = "^[A-Za-z0-9_-]{1,128}$") String meterId,
                                 @AuthenticationPrincipal FirebasePrincipal principal,
                                 @Valid @RequestBody CreateReadingRequest request) {
        return readingService.create(siteId, meterId, principal.uid(), request);
    }

    @GetMapping
    public List<ReadingSummary> list(@PathVariable @Pattern(regexp = "^[A-Za-z0-9_-]{1,128}$") String siteId,
                                     @PathVariable @Pattern(regexp = "^[A-Za-z0-9_-]{1,128}$") String meterId,
                                     @AuthenticationPrincipal FirebasePrincipal principal,
                                     @RequestParam(required = false) Instant from,
                                     @RequestParam(required = false) Instant to) {
        return readingService.list(siteId, meterId, principal.uid(), from, to);
    }
}
