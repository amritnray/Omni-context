package com.example.mcp.tools;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationContext;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.beans.factory.config.ConfigurableListableBeanFactory;
import org.springframework.core.env.Environment;

import javax.sql.DataSource;
import java.io.File;
import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.ResultSet;
import java.util.*;

/**
 * Suite of tools to help developers and LLMs onboard to a target application codebase.
 */
public class DeveloperOnboardingTools {

    private static final Logger logger = LoggerFactory.getLogger(DeveloperOnboardingTools.class);
    
    private final ApplicationContext applicationContext;
    private final Environment environment;

    public DeveloperOnboardingTools(ApplicationContext applicationContext, Environment environment) {
        this.applicationContext = applicationContext;
        this.environment = environment;
        logger.debug("DeveloperOnboardingTools initialized");
    }

    /**
     * Lists the file tree of the codebase, skipping common build/config/IDE directories.
     */
    public String getProjectStructure() {
        logger.info("Fetching project structure...");
        long startTime = System.currentTimeMillis();
        
        File rootDir = new File(".").getAbsoluteFile();
        StringBuilder sb = new StringBuilder();
        sb.append("Project Structure for: ").append(rootDir.getName()).append("\n");
        buildFileTree(rootDir, 0, sb);
        
        long duration = System.currentTimeMillis() - startTime;
        logger.info("Project structure fetched in {}ms", duration);
        
        return sb.toString();
    }

    private void buildFileTree(File dir, int depth, StringBuilder sb) {
        if (dir == null || !dir.exists()) {
            return;
        }

        File[] files = dir.listFiles();
        if (files == null) {
            return;
        }

        // Sort files to list directories first, then alphabetically
        Arrays.sort(files, (f1, f2) -> {
            if (f1.isDirectory() && !f2.isDirectory()) return -1;
            if (!f1.isDirectory() && f2.isDirectory()) return 1;
            return f1.getName().compareToIgnoreCase(f2.getName());
        });

        for (File f : files) {
            String name = f.getName();
            // Skip common build, metadata, and IDE folders
            if (name.startsWith(".") || 
                name.equals("target") || 
                name.equals("build") || 
                name.equals("node_modules") || 
                name.equals("bin") || 
                name.equals("out")) {
                continue;
            }

            for (int i = 0; i < depth; i++) {
                sb.append("  ");
            }
            sb.append(f.isDirectory() ? "📁 " : "📄 ").append(name).append("\n");

            if (f.isDirectory() && depth < 4) { // Cap depth at 4 to prevent massive outputs
                buildFileTree(f, depth + 1, sb);
            }
        }
    }

    /**
     * Lists all Spring Beans, their class types, and their active dependencies.
     * Filters out Spring framework internal beans to show only user-defined beans.
     */
    public Map<String, Object> getSpringBeans() {
        logger.info("Fetching Spring beans...");
        long startTime = System.currentTimeMillis();
        
        Map<String, Object> beanMap = new TreeMap<>();
        String[] beanNames = applicationContext.getBeanDefinitionNames();
        logger.debug("Found {} total bean definitions", beanNames.length);

        ConfigurableListableBeanFactory beanFactory = null;
        if (applicationContext instanceof ConfigurableApplicationContext) {
            beanFactory = ((ConfigurableApplicationContext) applicationContext).getBeanFactory();
        }

        int processedCount = 0;
        for (String name : beanNames) {
            Class<?> type = applicationContext.getType(name);
            String className = type != null ? type.getName() : "";
            
            // Skip framework-internal beans to keep output relevant to users
            if (isFrameworkBean(name, className)) {
                continue;
            }

            Map<String, Object> beanDetails = new HashMap<>();
            beanDetails.put("class", className.isEmpty() ? "Unknown" : className);

            if (beanFactory != null) {
                try {
                    String[] dependencies = beanFactory.getDependenciesForBean(name);
                    if (dependencies.length > 0) {
                        List<String> userDeps = new ArrayList<>();
                        for (String dep : dependencies) {
                            if (!dep.contains("org.springframework")) {
                                userDeps.add(dep);
                            }
                        }
                        if (!userDeps.isEmpty()) {
                            beanDetails.put("dependencies", userDeps);
                        }
                    }
                } catch (Exception ignored) {}
            }

            beanMap.put(name, beanDetails);
            processedCount++;
        }

        long duration = System.currentTimeMillis() - startTime;
        logger.info("Spring beans fetched: {} user beans found (from {} total) in {}ms", 
                processedCount, beanNames.length, duration);
        
        return beanMap;
    }

    private boolean isFrameworkBean(String beanName, String className) {
        // Exclude beans by class package
        String[] excludedPackagePrefixes = {
            "org.springframework.",
            "org.apache.",
            "com.zaxxer.hikari.",
            "com.fasterxml.jackson.",
            "org.hibernate.",
            "io.modelcontextprotocol.",
            "com.example.mcp.",
            "org.thymeleaf.",
            "com.sun."
        };
        for (String prefix : excludedPackagePrefixes) {
            if (className.startsWith(prefix)) {
                return true;
            }
        }
        
        // Exclude beans by name pattern (common Spring auto-config names)
        if (beanName.startsWith("org.springframework.") ||
            beanName.startsWith("spring.") ||
            beanName.startsWith("management.") ||
            beanName.contains("AutoConfiguration") ||
            beanName.contains("ObjectMapper")) {
            return true;
        }
        
        return false;
    }

    /**
     * Inspects active database DataSource and extracts database schema metadata (tables, columns, types).
     */
    public Map<String, Object> getDatabaseMetadata() {
        logger.info("Fetching database metadata...");
        long startTime = System.currentTimeMillis();
        
        Map<String, Object> dbMetadata = new TreeMap<>();
        
        try {
            DataSource dataSource = applicationContext.getBean(DataSource.class);
            logger.debug("DataSource found, connecting...");
            
            try (Connection conn = dataSource.getConnection()) {
                DatabaseMetaData metaData = conn.getMetaData();
                dbMetadata.put("databaseProduct", metaData.getDatabaseProductName());
                dbMetadata.put("databaseVersion", metaData.getDatabaseProductVersion());
                logger.debug("Connected to {} {}", metaData.getDatabaseProductName(), metaData.getDatabaseProductVersion());

                Map<String, List<Map<String, String>>> tables = new TreeMap<>();
                try (ResultSet rs = metaData.getTables(null, null, "%", new String[]{"TABLE"})) {
                    int tableCount = 0;
                    while (rs.next()) {
                        String tableName = rs.getString("TABLE_NAME");
                        List<Map<String, String>> columns = new ArrayList<>();

                        try (ResultSet cols = metaData.getColumns(null, null, tableName, "%")) {
                            while (cols.next()) {
                                Map<String, String> columnInfo = new LinkedHashMap<>();
                                columnInfo.put("columnName", cols.getString("COLUMN_NAME"));
                                columnInfo.put("typeName", cols.getString("TYPE_NAME"));
                                columnInfo.put("columnSize", cols.getString("COLUMN_SIZE"));
                                columnInfo.put("isNullable", cols.getString("IS_NULLABLE"));
                                columns.add(columnInfo);
                            }
                        }

                        tables.put(tableName, columns);
                        tableCount++;
                    }
                    logger.debug("Found {} tables", tableCount);
                }
                dbMetadata.put("tables", tables);
            }
        } catch (Exception e) {
            logger.warn("Failed to fetch database metadata: {}", e.getMessage());
            dbMetadata.put("status", "No active DataSource or unable to connect");
            dbMetadata.put("error", e.getMessage() != null ? e.getMessage() : e.toString());
        }

        long duration = System.currentTimeMillis() - startTime;
        logger.info("Database metadata fetched in {}ms", duration);
        
        return dbMetadata;
    }

    /**
     * Returns JVM and Application health metrics.
     */
    public Map<String, Object> getAppHealth() {
        Map<String, Object> health = new TreeMap<>();
        
        // JVM Runtime
        Runtime runtime = Runtime.getRuntime();
        Map<String, Object> jvm = new LinkedHashMap<>();
        jvm.put("availableProcessors", runtime.availableProcessors());
        jvm.put("freeMemoryMB", runtime.freeMemory() / (1024 * 1024));
        jvm.put("totalMemoryMB", runtime.totalMemory() / (1024 * 1024));
        jvm.put("maxMemoryMB", runtime.maxMemory() / (1024 * 1024));
        health.put("jvm", jvm);

        // Environment Profile Info
        Map<String, Object> envInfo = new LinkedHashMap<>();
        envInfo.put("activeProfiles", Arrays.asList(environment.getActiveProfiles()));
        envInfo.put("defaultProfiles", Arrays.asList(environment.getDefaultProfiles()));
        health.put("environment", envInfo);

        return health;
    }
}
