package com.aquatrace.api.meters;

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
@RequestMapping("/api/v1/sites/{siteId}/meters")
public class MeterController {
    private final MeterService meterService;

    public MeterController(MeterService meterService) {
        this.meterService = meterService;
    }

    @PostMapping
    public MeterSummary create(@PathVariable @Pattern(regexp = "^[A-Za-z0-9_-]{1,128}$") String siteId,
                               @AuthenticationPrincipal FirebasePrincipal principal,
                               @Valid @RequestBody CreateMeterRequest request) {
        return meterService.create(siteId, principal.uid(), request);
    }

    @GetMapping
    public List<MeterSummary> list(@PathVariable @Pattern(regexp = "^[A-Za-z0-9_-]{1,128}$") String siteId,
                                   @AuthenticationPrincipal FirebasePrincipal principal) {
        return meterService.list(siteId, principal.uid());
    }
}


