package com.aquatrace.api.sites;

import com.aquatrace.api.security.FirebasePrincipal;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Pattern;
import java.util.List;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.validation.annotation.Validated;

@RestController
@RequestMapping("/api/v1/sites")
public class SiteController {
    private final SiteService siteService;

    public SiteController(SiteService siteService) {
        this.siteService = siteService;
    }

    @PostMapping
    public SiteSummary create(@AuthenticationPrincipal FirebasePrincipal principal,
                              @Valid @RequestBody CreateSiteRequest request) {
        return siteService.create(principal.uid(), request);
    }

    @GetMapping
    public List<SiteSummary> list(@AuthenticationPrincipal FirebasePrincipal principal) {
        return siteService.listForUser(principal.uid());
    }

    @GetMapping("/{siteId}")
    public SiteSummary get(@PathVariable @Pattern(regexp = "^[A-Za-z0-9_-]{1,128}$") String siteId,
                           @AuthenticationPrincipal FirebasePrincipal principal) {
        return siteService.get(principal.uid(), siteId);
    }
}


