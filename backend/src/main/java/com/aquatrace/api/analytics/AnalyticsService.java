package com.aquatrace.api.analytics;

import com.aquatrace.api.common.ApiException;
import com.aquatrace.api.sites.SiteAuthorizationService;
import com.google.cloud.firestore.DocumentSnapshot;
import com.google.cloud.firestore.Firestore;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

@Service
public class AnalyticsService {
    private final Firestore firestore;
    private final SiteAuthorizationService authorizationService;
    private final long timeoutSeconds;

    public AnalyticsService(Firestore firestore, SiteAuthorizationService authorizationService,
            @Value("${aquatrace.firestore.operation-timeout-seconds:4}") long timeoutSeconds) {
        this.firestore = firestore; this.authorizationService = authorizationService; this.timeoutSeconds = timeoutSeconds;
    }

    public List<DailyAggregateSummary> list(String siteId, String uid, LocalDate from, LocalDate to) {
        authorizationService.requireMembership(siteId, uid);
        long days = ChronoUnit.DAYS.between(from, to);
        if (days < 0 || days > 89) throw new ApiException(HttpStatus.BAD_REQUEST, "INVALID_DATE_RANGE", "Choose an inclusive date range of at most 90 days.");
        try {
            var query = firestore.collection("sites").document(siteId).collection("dailyAggregates")
                    .whereGreaterThanOrEqualTo("date", from.toString())
                    .whereLessThanOrEqualTo("date", to.toString())
                    .orderBy("date").limit(90);
            List<DailyAggregateSummary> results = new ArrayList<>();
            for (DocumentSnapshot document : query.get().get(timeoutSeconds, TimeUnit.SECONDS).getDocuments()) {
                results.add(new DailyAggregateSummary(document.getString("date"), document.getString("timezone"),
                        number(document, "totalUsageLitres"), optionalNumber(document, "mainMeterUsageLitres"),
                        optionalNumber(document, "submeterTotalUsageLitres"), optionalNumber(document, "reconciliationDifferenceLitres"),
                        intNumber(document, "meterCount"), intNumber(document, "intervalCount"), intNumber(document, "gapIntervalCount")));
            }
            return results;
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt(); throw unavailable();
        } catch (ExecutionException | TimeoutException exception) { throw unavailable(); }
    }

    private static double number(DocumentSnapshot document, String field) {
        Number value = document.get(field, Number.class); return value == null ? 0 : value.doubleValue();
    }
    private static Double optionalNumber(DocumentSnapshot document, String field) {
        Number value = document.get(field, Number.class); return value == null ? null : value.doubleValue();
    }
    private static int intNumber(DocumentSnapshot document, String field) { return (int) number(document, field); }
    private ApiException unavailable() { return new ApiException(HttpStatus.SERVICE_UNAVAILABLE, "FIRESTORE_UNAVAILABLE", "The data store could not complete this request. Please retry."); }
}
