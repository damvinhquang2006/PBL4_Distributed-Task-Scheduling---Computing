package com.pbl4.master.scheduler;

public record ResourceThresholds(double cpuHigh, double cpuLow, double ramHigh,
                                 double ramLow, double heapHigh, double heapLow) {
    public ResourceThresholds {
        check(cpuHigh, cpuLow); check(ramHigh, ramLow); check(heapHigh, heapLow);
    }
    private static void check(double high, double low) {
        if (!Double.isFinite(high) || !Double.isFinite(low) || low < 0 || high > 100 || low >= high)
            throw new IllegalArgumentException("Thresholds require 0 <= resume < pause <= 100");
    }
    public static ResourceThresholds defaults() { return new ResourceThresholds(85, 75, 90, 80, 85, 75); }
}
