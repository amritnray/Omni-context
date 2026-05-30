package com.example.mcp.tools;

import com.example.mcp.logging.McpLogAppender;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Analyzes the log buffer for ERROR and WARN entries, groups them by exception class
 * with occurrence counts and latest timestamps. Complements get_recent_logs for
 * focused error diagnosis.
 */
public class ErrorSummaryTool {

    private static final Logger logger = LoggerFactory.getLogger(ErrorSummaryTool.class);
    private static final Pattern LOG_LINE_PATTERN = Pattern.compile("^\\[(.+?)\\]\\s*\\[(.+?)\\]\\s*(.+?)\\s*-\\s*(.*)$");
    private static final Pattern EXCEPTION_PATTERN = Pattern.compile("([a-zA-Z_$][a-zA-Z0-9_$]*(?:\\.[a-zA-Z_$][a-zA-Z0-9_$]*)*Exception|[a-zA-Z_$][a-zA-Z0-9_$]*(?:\\.[a-zA-Z_$][a-zA-Z0-9_$]*)*Error)");

    public Map<String, Object> getErrorSummary() {
        logger.info("Analyzing log buffer for errors...");
        long startTime = System.currentTimeMillis();

        Map<String, Object> result = new LinkedHashMap<>();

        List<String> logs = getRecentLogs();
        result.put("totalLogEntries", logs.size());

        List<String> errorLines = new ArrayList<>();
        List<String> warnLines = new ArrayList<>();

        for (String line : logs) {
            Matcher m = LOG_LINE_PATTERN.matcher(line);
            if (!m.matches()) continue;

            String level = m.group(2).trim();
            if ("ERROR".equalsIgnoreCase(level)) {
                errorLines.add(line);
            } else if ("WARN".equalsIgnoreCase(level) || "WARNING".equalsIgnoreCase(level)) {
                warnLines.add(line);
            }
        }

        result.put("errorCount", errorLines.size());
        result.put("warnCount", warnLines.size());

        result.put("errorGroups", groupByException(errorLines));
        result.put("warnGroups", groupByException(warnLines));

        // Last 5 raw error lines for context
        List<String> recentErrors = errorLines.subList(Math.max(0, errorLines.size() - 5), errorLines.size());
        result.put("recentErrors", recentErrors);

        long duration = System.currentTimeMillis() - startTime;
        logger.info("Error summary completed: {} errors, {} warnings in {}ms", errorLines.size(), warnLines.size(), duration);

        return result;
    }

    private List<Map<String, Object>> groupByException(List<String> logLines) {
        // Group: exceptionClass -> {count, latestTimestamp, sampleMessage, sampleLogger}
        Map<String, Map<String, Object>> groups = new LinkedHashMap<>();

        for (String line : logLines) {
            Matcher m = LOG_LINE_PATTERN.matcher(line);
            if (!m.matches()) continue;

            String timestamp = m.group(1).trim();
            String loggerName = m.group(3).trim();
            String message = m.group(4).trim();

            // Extract exception class from message
            String exceptionClass = extractExceptionClass(message);
            if (exceptionClass == null) {
                // Group by logger + message prefix if no exception found
                String msgPrefix = message.length() > 80 ? message.substring(0, 80) : message;
                exceptionClass = "[" + loggerName + "] " + msgPrefix;
            }

            Map<String, Object> group = groups.computeIfAbsent(exceptionClass, k -> {
                Map<String, Object> g = new LinkedHashMap<>();
                g.put("exceptionClass", k);
                g.put("count", 0);
                g.put("latestOccurrence", "");
                g.put("sampleMessage", "");
                g.put("sampleLogger", "");
                return g;
            });

            group.put("count", (int) group.get("count") + 1);
            group.put("latestOccurrence", timestamp);
            group.put("sampleMessage", message);
            group.put("sampleLogger", loggerName);
        }

        // Sort by count descending
        List<Map<String, Object>> sorted = new ArrayList<>(groups.values());
        sorted.sort((a, b) -> Integer.compare((int) b.get("count"), (int) a.get("count")));

        return sorted;
    }

    private String extractExceptionClass(String message) {
        // Look for fully qualified or simple exception class names
        Matcher m = EXCEPTION_PATTERN.matcher(message);
        if (m.find()) {
            String full = m.group(0);
            // Return simple name if fully qualified, otherwise return as-is
            int lastDot = full.lastIndexOf('.');
            return lastDot >= 0 ? full.substring(lastDot + 1) : full;
        }
        return null;
    }

    private List<String> getRecentLogs() {
        McpLogAppender appender = McpLogAppender.findAppender();
        if (appender != null) {
            return appender.getRecentLogs();
        }
        return Collections.emptyList();
    }
}
