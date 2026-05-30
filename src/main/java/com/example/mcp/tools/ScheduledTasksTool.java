package com.example.mcp.tools;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationContext;
import org.springframework.core.env.Environment;
import org.springframework.scheduling.annotation.Async;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.scheduling.TaskScheduler;

import java.lang.reflect.Method;
import java.util.*;

/**
 * Discovers @Scheduled methods, TaskScheduler beans, and @Async methods
 * in the application context for understanding background task behavior.
 */
public class ScheduledTasksTool {

    private static final Logger logger = LoggerFactory.getLogger(ScheduledTasksTool.class);

    private final ApplicationContext applicationContext;
    private final Environment environment;

    public ScheduledTasksTool(ApplicationContext applicationContext, Environment environment) {
        this.applicationContext = applicationContext;
        this.environment = environment;
    }

    public Map<String, Object> getScheduledTasks() {
        logger.info("Discovering scheduled tasks...");
        long startTime = System.currentTimeMillis();

        Map<String, Object> result = new LinkedHashMap<>();

        // Scan for @Scheduled methods
        List<Map<String, Object>> scheduledMethods = new ArrayList<>();
        // Scan for @Async methods
        List<Map<String, Object>> asyncMethods = new ArrayList<>();

        String[] beanNames = applicationContext.getBeanDefinitionNames();
        for (String beanName : beanNames) {
            Class<?> beanClass;
            try {
                beanClass = applicationContext.getType(beanName);
            } catch (Exception e) {
                continue;
            }
            if (beanClass == null) continue;

            for (Method method : beanClass.getDeclaredMethods()) {
                Scheduled scheduled = method.getAnnotation(Scheduled.class);
                if (scheduled != null) {
                    Map<String, Object> taskInfo = new LinkedHashMap<>();
                    taskInfo.put("beanName", beanName);
                    taskInfo.put("beanClass", beanClass.getName());
                    taskInfo.put("methodName", method.getName());

                    String cron = scheduled.cron();
                    if (!cron.isEmpty()) {
                        taskInfo.put("cron", resolveProperty(cron));
                    }

                    String fixedDelay = scheduled.fixedDelayString();
                    if (!fixedDelay.isEmpty()) {
                        taskInfo.put("fixedDelay", resolveProperty(fixedDelay));
                    } else if (scheduled.fixedDelay() > 0) {
                        taskInfo.put("fixedDelayMs", scheduled.fixedDelay());
                    }

                    String fixedRate = scheduled.fixedRateString();
                    if (!fixedRate.isEmpty()) {
                        taskInfo.put("fixedRate", resolveProperty(fixedRate));
                    } else if (scheduled.fixedRate() > 0) {
                        taskInfo.put("fixedRateMs", scheduled.fixedRate());
                    }

                    String initialDelay = scheduled.initialDelayString();
                    if (!initialDelay.isEmpty()) {
                        taskInfo.put("initialDelay", resolveProperty(initialDelay));
                    } else if (scheduled.initialDelay() > 0) {
                        taskInfo.put("initialDelayMs", scheduled.initialDelay());
                    }

                    String zone = scheduled.zone();
                    if (!zone.isEmpty()) {
                        taskInfo.put("zone", resolveProperty(zone));
                    }

                    if (!scheduled.fixedDelayString().isEmpty() || scheduled.fixedDelay() > 0) {
                        taskInfo.put("type", "FIXED_DELAY");
                    } else if (!scheduled.fixedRateString().isEmpty() || scheduled.fixedRate() > 0) {
                        taskInfo.put("type", "FIXED_RATE");
                    } else if (!cron.isEmpty()) {
                        taskInfo.put("type", "CRON");
                    }

                    scheduledMethods.add(taskInfo);
                }

                Async async = method.getAnnotation(Async.class);
                if (async != null) {
                    Map<String, Object> asyncInfo = new LinkedHashMap<>();
                    asyncInfo.put("beanName", beanName);
                    asyncInfo.put("beanClass", beanClass.getName());
                    asyncInfo.put("methodName", method.getName());

                    String value = async.value();
                    if (!value.isEmpty()) {
                        asyncInfo.put("executor", value);
                    }

                    asyncMethods.add(asyncInfo);
                }
            }
        }

        result.put("scheduledMethods", scheduledMethods);
        result.put("asyncMethods", asyncMethods);

        // TaskScheduler beans
        List<Map<String, Object>> schedulerBeans = new ArrayList<>();
        try {
            Map<String, TaskScheduler> schedulers = applicationContext.getBeansOfType(TaskScheduler.class);
            for (Map.Entry<String, TaskScheduler> entry : schedulers.entrySet()) {
                Map<String, Object> schedulerInfo = new LinkedHashMap<>();
                schedulerInfo.put("beanName", entry.getKey());
                schedulerInfo.put("beanClass", entry.getValue().getClass().getName());
                schedulerBeans.add(schedulerInfo);
            }
        } catch (Exception e) {
            logger.debug("No TaskScheduler beans found: {}", e.getMessage());
        }
        result.put("taskSchedulerBeans", schedulerBeans);

        long duration = System.currentTimeMillis() - startTime;
        logger.info("Scheduled task discovery completed: {} scheduled, {} async, {} schedulers in {}ms",
                scheduledMethods.size(), asyncMethods.size(), schedulerBeans.size(), duration);

        return result;
    }

    private String resolveProperty(String value) {
        if (value != null && value.startsWith("${") && value.endsWith("}")) {
            String key = value.substring(2, value.length() - 1);
            String resolved = environment.getProperty(key);
            return resolved != null ? resolved : value;
        }
        return value;
    }
}
