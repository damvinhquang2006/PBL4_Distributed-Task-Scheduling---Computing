package com.pbl4.worker;

import com.pbl4.core.model.ResourceMetrics;
import java.lang.management.ManagementFactory;
import java.util.ArrayDeque;
import java.util.function.Function;

public class ResourceSampler {
    private final ArrayDeque<ResourceMetrics> samples = new ArrayDeque<>();
    public synchronized void sample() {
        Double cpu = null, ram = null;
        if (ManagementFactory.getOperatingSystemMXBean() instanceof com.sun.management.OperatingSystemMXBean os) {
            cpu = ResourceMetrics.valid(os.getCpuLoad() * 100);
            long total = os.getTotalMemorySize();
            if (total > 0) ram = ResourceMetrics.valid(100.0 * (total - os.getFreeMemorySize()) / total);
        }
        Runtime rt = Runtime.getRuntime();
        Double heap = ResourceMetrics.valid(100.0 * (rt.totalMemory() - rt.freeMemory()) / rt.maxMemory());
        samples.addLast(new ResourceMetrics(cpu, ram, heap, 0));
        if (samples.size() > 3) samples.removeFirst();
    }
    public synchronized ResourceMetrics current(int running) {
        return new ResourceMetrics(average(m -> m.cpu), average(m -> m.ram), average(m -> m.heap), running);
    }
    private Double average(Function<ResourceMetrics, Double> getter) {
        return samples.stream().map(getter).filter(java.util.Objects::nonNull)
                .mapToDouble(Double::doubleValue).average().stream().boxed().findFirst().orElse(null);
    }
}
