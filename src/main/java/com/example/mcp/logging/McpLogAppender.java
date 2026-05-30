package com.example.mcp.logging;

import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.AppenderBase;
import io.modelcontextprotocol.spec.McpSchema.LoggingLevel;
import io.modelcontextprotocol.spec.McpSchema.LoggingMessageNotification;
import org.slf4j.LoggerFactory;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Date;
import java.util.LinkedList;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;

public class McpLogAppender extends AppenderBase<ILoggingEvent> {

    private static final int BUFFER_SIZE = 200;
    private static final org.slf4j.Logger selfLogger = LoggerFactory.getLogger(McpLogAppender.class);
    private final AtomicLong logsProcessed = new AtomicLong(0);
    
    private final List<String> logBuffer = Collections.synchronizedList(new LinkedList<String>() {
        @Override
        public boolean add(String e) {
            boolean result = super.add(e);
            while (size() > BUFFER_SIZE) {
                removeFirst();
            }
            return result;
        }
    });

    private static volatile Consumer<LoggingMessageNotification> logListener;

    public static void setLogListener(Consumer<LoggingMessageNotification> listener) {
        logListener = listener;
    }

    @Override
    protected void append(ILoggingEvent eventObject) {
        if (eventObject == null) {
            return;
        }

        String loggerName = eventObject.getLoggerName();

        // Filter out MCP transport and our own logs to prevent infinite loop
        if (loggerName != null && (
                loggerName.startsWith("io.modelcontextprotocol") ||
                loggerName.startsWith("com.example.mcp") ||
                loggerName.startsWith("org.apache.catalina") ||
                loggerName.startsWith("org.apache.coyote"))) {
            return;
        }

        long processed = logsProcessed.incrementAndGet();
        if (processed % 100 == 0) {
            selfLogger.debug("McpLogAppender has processed {} log events, buffer size: {}", processed, logBuffer.size());
        }

        String formattedMessage = eventObject.getFormattedMessage();
        String levelStr = eventObject.getLevel().toString();
        
        String timeStamp = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS").format(new Date(eventObject.getTimeStamp()));
        String fullLogLine = String.format("[%s] [%s] %s - %s", timeStamp, levelStr, loggerName, formattedMessage);
        
        logBuffer.add(fullLogLine);

        Consumer<LoggingMessageNotification> listener = logListener;
        if (listener != null) {
            LoggingLevel mcpLevel = mapToMcpLevel(eventObject.getLevel());
            try {
                // Create notification with data map for SDK 0.8.0 compatibility
                LoggingMessageNotification notification = new LoggingMessageNotification(
                    mcpLevel,
                    loggerName,
                    formattedMessage
                );
                listener.accept(notification);
            } catch (Exception ignored) {
                // Prevent MCP transmission errors from crashing target logging pipeline
            }
        }
    }

    private LoggingLevel mapToMcpLevel(ch.qos.logback.classic.Level level) {
        if (level == null) {
            return LoggingLevel.INFO;
        }
        if (level.isGreaterOrEqual(ch.qos.logback.classic.Level.ERROR)) {
            return LoggingLevel.ERROR;
        } else if (level.isGreaterOrEqual(ch.qos.logback.classic.Level.WARN)) {
            return LoggingLevel.WARNING;
        } else if (level.isGreaterOrEqual(ch.qos.logback.classic.Level.INFO)) {
            return LoggingLevel.INFO;
        } else {
            return LoggingLevel.DEBUG;
        }
    }

    public List<String> getRecentLogs() {
        synchronized (logBuffer) {
            selfLogger.debug("Retrieving {} recent logs from buffer", logBuffer.size());
            return new ArrayList<>(logBuffer);
        }
    }

    /**
     * Static helper to find the McpLogAppender from the Logback context.
     */
    public static McpLogAppender findAppender() {
        try {
            ch.qos.logback.classic.LoggerContext ctx = (ch.qos.logback.classic.LoggerContext) org.slf4j.LoggerFactory.getILoggerFactory();
            ch.qos.logback.classic.Logger rootLogger = ctx.getLogger(org.slf4j.Logger.ROOT_LOGGER_NAME);
            java.util.Iterator<ch.qos.logback.core.Appender<ILoggingEvent>> iterator = rootLogger.iteratorForAppenders();
            while (iterator.hasNext()) {
                ch.qos.logback.core.Appender<ILoggingEvent> appender = iterator.next();
                if (appender instanceof McpLogAppender) {
                    return (McpLogAppender) appender;
                }
            }
        } catch (Exception ignored) {}
        return null;
    }
}
