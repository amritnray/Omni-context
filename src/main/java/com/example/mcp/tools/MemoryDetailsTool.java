package com.example.mcp.tools;

import java.lang.management.GarbageCollectorMXBean;
import java.lang.management.ManagementFactory;
import java.lang.management.MemoryMXBean;
import java.lang.management.MemoryPoolMXBean;
import java.lang.management.MemoryUsage;
import java.util.*;

/**
 * Detailed JVM memory analysis including heap/non-heap pools with usage percentages,
 * per-pool breakdown, and garbage collector statistics.
 */
public class MemoryDetailsTool {

    public Map<String, Object> getMemoryDetails() {
        Map<String, Object> result = new LinkedHashMap<>();

        MemoryMXBean memoryMXBean = ManagementFactory.getMemoryMXBean();

        // Heap memory
        MemoryUsage heapUsage = memoryMXBean.getHeapMemoryUsage();
        result.put("heap", formatMemoryUsage(heapUsage));

        // Non-heap memory
        MemoryUsage nonHeapUsage = memoryMXBean.getNonHeapMemoryUsage();
        result.put("nonHeap", formatMemoryUsage(nonHeapUsage));

        // Memory pools
        List<Map<String, Object>> pools = new ArrayList<>();
        for (MemoryPoolMXBean pool : ManagementFactory.getMemoryPoolMXBeans()) {
            Map<String, Object> poolInfo = new LinkedHashMap<>();
            poolInfo.put("name", pool.getName());
            poolInfo.put("type", pool.getType().name());

            MemoryUsage usage = pool.getUsage();
            long usedMB = usage.getUsed() / (1024 * 1024);
            long maxMB = usage.getMax() == -1 ? -1 : usage.getMax() / (1024 * 1024);
            long committedMB = usage.getCommitted() / (1024 * 1024);

            poolInfo.put("usedMB", usedMB);
            poolInfo.put("committedMB", committedMB);
            poolInfo.put("maxMB", maxMB);

            if (usage.getMax() > 0) {
                poolInfo.put("usagePercent", Math.round(usage.getUsed() * 100.0 / usage.getMax() * 10.0) / 10.0);
            }

            poolInfo.put("managerNames", Arrays.asList(pool.getMemoryManagerNames()));
            pools.add(poolInfo);
        }
        result.put("memoryPools", pools);

        // Garbage collectors
        List<Map<String, Object>> gcList = new ArrayList<>();
        for (GarbageCollectorMXBean gc : ManagementFactory.getGarbageCollectorMXBeans()) {
            Map<String, Object> gcInfo = new LinkedHashMap<>();
            gcInfo.put("name", gc.getName());
            gcInfo.put("collectionCount", gc.getCollectionCount());
            gcInfo.put("collectionTimeMs", gc.getCollectionTime());
            gcInfo.put("memoryPoolNames", Arrays.asList(gc.getMemoryPoolNames()));
            gcList.add(gcInfo);
        }
        result.put("garbageCollectors", gcList);

        return result;
    }

    private Map<String, Object> formatMemoryUsage(MemoryUsage usage) {
        Map<String, Object> info = new LinkedHashMap<>();
        info.put("initMB", usage.getInit() / (1024 * 1024));
        info.put("usedMB", usage.getUsed() / (1024 * 1024));
        info.put("committedMB", usage.getCommitted() / (1024 * 1024));
        long maxMB = usage.getMax() == -1 ? -1 : usage.getMax() / (1024 * 1024);
        info.put("maxMB", maxMB);
        if (usage.getMax() > 0) {
            info.put("usagePercent", Math.round(usage.getUsed() * 100.0 / usage.getMax() * 10.0) / 10.0);
        }
        return info;
    }
}
