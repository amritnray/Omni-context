package com.example.mcp.tools;

import java.lang.management.ManagementFactory;
import java.lang.management.ThreadInfo;
import java.lang.management.ThreadMXBean;
import java.util.*;

/**
 * Captures a JVM thread dump showing all threads, their states, lock information,
 * stack traces, and detected deadlocks. Critical for diagnosing hangs and contention.
 */
public class ThreadDumpTool {

    private static final int MAX_STACK_FRAMES = 10;

    public Map<String, Object> getThreadDump() {
        ThreadMXBean threadMXBean = ManagementFactory.getThreadMXBean();

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("threadCount", threadMXBean.getThreadCount());
        result.put("daemonThreadCount", threadMXBean.getDaemonThreadCount());
        result.put("peakThreadCount", threadMXBean.getPeakThreadCount());
        result.put("totalStartedThreadCount", threadMXBean.getTotalStartedThreadCount());

        // Deadlock detection
        long[] deadlockedIds = threadMXBean.findDeadlockedThreads();
        if (deadlockedIds != null && deadlockedIds.length > 0) {
            List<String> deadlockedInfo = new ArrayList<>();
            ThreadInfo[] deadlockInfos = threadMXBean.getThreadInfo(deadlockedIds, MAX_STACK_FRAMES);
            for (ThreadInfo info : deadlockInfos) {
                if (info != null) {
                    deadlockedInfo.add(info.getThreadName() + " (id=" + info.getThreadId() + ")");
                }
            }
            result.put("deadlockedThreads", deadlockedInfo);
        } else {
            result.put("deadlockedThreads", Collections.emptyList());
        }

        // Thread details
        long[] allIds = threadMXBean.getAllThreadIds();
        ThreadInfo[] allInfos = threadMXBean.getThreadInfo(allIds, MAX_STACK_FRAMES);

        Set<Long> deadlockedSet = new HashSet<>();
        if (deadlockedIds != null) {
            for (long id : deadlockedIds) {
                deadlockedSet.add(id);
            }
        }

        List<Map<String, Object>> threads = new ArrayList<>();
        for (ThreadInfo info : allInfos) {
            if (info == null) continue;

            Map<String, Object> thread = new LinkedHashMap<>();
            thread.put("name", info.getThreadName());
            thread.put("id", info.getThreadId());
            thread.put("state", info.getThreadState().name());
            thread.put("isDaemon", info.isDaemon());
            thread.put("isDeadlocked", deadlockedSet.contains(info.getThreadId()));

            if (info.getLockName() != null) {
                thread.put("lockName", info.getLockName());
            }
            if (info.getLockOwnerName() != null) {
                thread.put("lockOwnerName", info.getLockOwnerName());
                thread.put("lockOwnerId", info.getLockOwnerId());
            }

            StackTraceElement[] stack = info.getStackTrace();
            if (stack.length > 0) {
                List<String> stackLines = new ArrayList<>();
                for (int i = 0; i < Math.min(stack.length, MAX_STACK_FRAMES); i++) {
                    stackLines.add("  at " + stack[i].toString());
                }
                if (stack.length > MAX_STACK_FRAMES) {
                    stackLines.add("  ... " + (stack.length - MAX_STACK_FRAMES) + " more frames");
                }
                thread.put("stackTrace", stackLines);
            }

            threads.add(thread);
        }

        // Sort: deadlocked first, then blocked, then others
        Map<String, Integer> statePriority = Map.of(
            "BLOCKED", 0, "WAITING", 1, "TIMED_WAITING", 2, "RUNNABLE", 3, "NEW", 4, "TERMINATED", 5
        );
        threads.sort(Comparator
            .comparingInt((Map<String, Object> t) -> t.containsKey("isDeadlocked") && Boolean.TRUE.equals(t.get("isDeadlocked")) ? -1 : 0)
            .thenComparingInt(t -> statePriority.getOrDefault((String) t.get("state"), 99)));

        result.put("threads", threads);
        return result;
    }
}
