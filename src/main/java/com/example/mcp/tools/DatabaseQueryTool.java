package com.example.mcp.tools;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.support.rowset.SqlRowSet;
import org.springframework.util.StringUtils;

import javax.sql.DataSource;
import java.util.*;
import java.util.regex.Pattern;

/**
 * Executes read-only SQL queries against the database.
 * Only allows SELECT and SHOW statements for security.
 */
public class DatabaseQueryTool {

    private static final Logger logger = LoggerFactory.getLogger(DatabaseQueryTool.class);

    private final JdbcTemplate jdbcTemplate;

    // Pattern to detect SQL keywords that indicate modification operations
    private static final Pattern MODIFICATION_PATTERN = Pattern.compile(
            "\\b(INSERT|UPDATE|DELETE|DROP|ALTER|CREATE|TRUNCATE|MERGE)\\b",
            Pattern.CASE_INSENSITIVE);

    // Pattern to validate that query starts with SELECT or SHOW
    private static final Pattern VALID_START_PATTERN = Pattern.compile(
            "^\\s*(SELECT|SHOW)\\s+",
            Pattern.CASE_INSENSITIVE);

    public DatabaseQueryTool(DataSource dataSource) {
        this.jdbcTemplate = new JdbcTemplate(dataSource);
    }

    /**
     * Executes a read-only SQL query and returns the results.
     *
     * @param sql The SQL query to execute (must be SELECT or SHOW)
     * @return Map containing query results or error information
     */
    public Map<String, Object> executeQuery(String sql) {
        Map<String, Object> result = new LinkedHashMap<>();

        try {
            // Validate that the query is not null or empty
            if (!StringUtils.hasText(sql)) {
                result.put("success", false);
                result.put("error", "SQL query cannot be empty");
                return result;
            }

            String trimmedSql = sql.trim();

            // Check that query starts with SELECT or SHOW
            if (!VALID_START_PATTERN.matcher(trimmedSql).find()) {
                result.put("success", false);
                result.put("error", "Only SELECT and SHOW statements are allowed");
                return result;
            }

            // Check for any modification operations
            if (MODIFICATION_PATTERN.matcher(trimmedSql).find()) {
                result.put("success", false);
                result.put("error", "Modification operations (INSERT, UPDATE, DELETE, DROP, ALTER, CREATE, TRUNCATE, MERGE) are not allowed");
                return result;
            }

            logger.debug("Executing SQL query: {}", truncatedSql(trimmedSql, 200));

            // Execute the query
            List<Map<String, Object>> rows = jdbcTemplate.queryForList(trimmedSql);

            // Extract column names from the first row if available
            List<String> columns = new ArrayList<>();
            if (!rows.isEmpty()) {
                columns.addAll(rows.get(0).keySet());
            }

            result.put("success", true);
            result.put("rows", rows);
            result.put("columns", columns);
            result.put("rowCount", rows.size());

            logger.info("Query executed successfully, returned {} rows", rows.size());

        } catch (Exception e) {
            logger.error("Error executing SQL query: {}", e.getMessage(), e);
            result.put("success", false);
            result.put("error", e.getMessage());
            result.put("rows", Collections.emptyList());
            result.put("columns", Collections.emptyList());
            result.put("rowCount", 0);
        }

        return result;
    }

    /**
     * Truncates a string to the specified maximum length for logging purposes.
     */
    private String truncatedSql(String sql, int maxLength) {
        if (sql.length() <= maxLength) {
            return sql;
        }
        return sql.substring(0, maxLength - 3) + "...";
    }
}