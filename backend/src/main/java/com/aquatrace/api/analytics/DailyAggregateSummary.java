package com.aquatrace.api.analytics;

public record DailyAggregateSummary(String date, String timezone, double totalUsageLitres,
                                    Double mainMeterUsageLitres, Double submeterTotalUsageLitres,
                                    Double reconciliationDifferenceLitres, int meterCount,
                                    int intervalCount, int gapIntervalCount) {
}
