package com.aquatrace.api.alerts;

import java.time.Instant;
import java.util.Map;

public record AlertSummary(String alertId, String siteId, String type, String severity, String meterId,
                           Instant detectedAt, Map<String, Object> observedValue, Map<String, Object> rule,
                           String explanation, String recommendedInvestigation, String status,
                           String acknowledgedBy) {
}
