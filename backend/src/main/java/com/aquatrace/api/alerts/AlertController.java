package com.aquatrace.api.alerts;

import com.aquatrace.api.security.FirebasePrincipal;
import jakarta.validation.constraints.Pattern;
import java.util.List;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@Validated
@RestController
@RequestMapping("/api/v1/sites/{siteId}/alerts")
public class AlertController {
    private final AlertService alertService;

    public AlertController(AlertService alertService) { this.alertService = alertService; }

    @GetMapping
    public List<AlertSummary> list(@PathVariable @Pattern(regexp = "^[A-Za-z0-9_-]{1,128}$") String siteId,
            @AuthenticationPrincipal FirebasePrincipal principal, @RequestParam(required = false) String status) {
        return alertService.list(siteId, principal.uid(), status);
    }

    @PatchMapping("/{alertId}/acknowledge")
    public AlertSummary acknowledge(@PathVariable @Pattern(regexp = "^[A-Za-z0-9_-]{1,128}$") String siteId,
            @PathVariable @Pattern(regexp = "^[A-Za-z0-9_-]{1,128}$") String alertId,
            @AuthenticationPrincipal FirebasePrincipal principal) {
        return alertService.transition(siteId, alertId, principal.uid(), "ACKNOWLEDGED");
    }

    @PatchMapping("/{alertId}/resolve")
    public AlertSummary resolve(@PathVariable @Pattern(regexp = "^[A-Za-z0-9_-]{1,128}$") String siteId,
            @PathVariable @Pattern(regexp = "^[A-Za-z0-9_-]{1,128}$") String alertId,
            @AuthenticationPrincipal FirebasePrincipal principal) {
        return alertService.transition(siteId, alertId, principal.uid(), "RESOLVED");
    }
}
