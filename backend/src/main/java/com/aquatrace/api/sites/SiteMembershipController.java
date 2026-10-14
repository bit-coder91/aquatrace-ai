package com.aquatrace.api.sites;
import jakarta.validation.constraints.Pattern;

import com.aquatrace.api.security.FirebasePrincipal;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.validation.annotation.Validated;

@RestController
@RequestMapping("/api/v1/sites/{siteId}/membership")
public class SiteMembershipController {
    private final SiteAuthorizationService authorizationService;

    public SiteMembershipController(SiteAuthorizationService authorizationService) {
        this.authorizationService = authorizationService;
    }

    @GetMapping
    public SiteMembership getMembership(@PathVariable @Pattern(regexp = "^[A-Za-z0-9_-]{1,128}$") String siteId,
                                        @AuthenticationPrincipal FirebasePrincipal principal) {
        return authorizationService.requireMembership(siteId, principal.uid());
    }
}


