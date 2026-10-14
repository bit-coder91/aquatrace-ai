package com.aquatrace.api.ingestion;

import com.aquatrace.api.security.FirebasePrincipal;
import jakarta.validation.constraints.Pattern;
import java.util.List;
import org.springframework.http.MediaType;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

@Validated
@RestController
@RequestMapping("/api/v1/sites/{siteId}/ingestion-jobs")
public class IngestionController {
    private final IngestionService ingestionService;

    public IngestionController(IngestionService ingestionService) {
        this.ingestionService = ingestionService;
    }

    @PostMapping(consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public IngestionJobSummary upload(@PathVariable @Pattern(regexp = "^[A-Za-z0-9_-]{1,128}$") String siteId,
                                     @AuthenticationPrincipal FirebasePrincipal principal,
                                     @RequestPart("file") MultipartFile file) {
        return ingestionService.upload(siteId, principal.uid(), file);
    }

    @GetMapping
    public List<IngestionJobSummary> list(@PathVariable @Pattern(regexp = "^[A-Za-z0-9_-]{1,128}$") String siteId,
                                          @AuthenticationPrincipal FirebasePrincipal principal) {
        return ingestionService.list(siteId, principal.uid());
    }

    @GetMapping("/{jobId}")
    public IngestionJobSummary get(@PathVariable @Pattern(regexp = "^[A-Za-z0-9_-]{1,128}$") String siteId,
                                   @PathVariable @Pattern(regexp = "^[A-Za-z0-9_-]{1,128}$") String jobId,
                                   @AuthenticationPrincipal FirebasePrincipal principal) {
        return ingestionService.get(siteId, jobId, principal.uid());
    }
}
