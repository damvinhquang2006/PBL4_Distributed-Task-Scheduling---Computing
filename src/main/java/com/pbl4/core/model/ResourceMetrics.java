package com.pbl4.core.model;

/** Percentages; null means the platform did not provide a valid sample. */
public class ResourceMetrics {
    public Double cpu;
    public Double ram;
    public Double heap;
    public int runningTasks;
    public ResourceMetrics() {}
    public ResourceMetrics(Double cpu, Double ram, Double heap, int runningTasks) {
        this.cpu = valid(cpu); this.ram = valid(ram); this.heap = valid(heap);
        this.runningTasks = Math.max(0, runningTasks);
    }
    public static Double valid(Double value) {
        return value != null && Double.isFinite(value) && value >= 0 && value <= 100 ? value : null;
    }
    private static String display(Double value) {
        return valid(value) == null ? "UNKNOWN" : String.format("%.1f%%", value);
    }
    @Override public String toString() {
        return "CPU=" + display(cpu) + " RAM=" + display(ram) + " HEAP=" + display(heap);
    }
}
