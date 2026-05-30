package com.example.mcp.tools;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.env.ConfigurableEnvironment;
import org.springframework.core.env.EnumerablePropertySource;
import org.springframework.core.env.Environment;
import org.springframework.core.env.PropertySource;

import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * Accesses and masks application properties from the Spring Environment.
 */
public class PropertiesTool {

    private static final Logger logger = LoggerFactory.getLogger(PropertiesTool.class);
    
    private final Environment environment;

    // Keywords that indicate a sensitive property to mask
    private static final List<String> SENSITIVE_KEYWORDS = Arrays.asList(
        "password", "secret", "key", "token", "credential", 
        "private", "pwd", "auth", "passphrase", "signature", "api-key", "apikey"
    );

    public PropertiesTool(Environment environment) {
        this.environment = environment;
        logger.debug("PropertiesTool initialized");
    }

    /**
     * Retrieves all application properties, masking any sensitive values.
     */
    public Map<String, Object> getMaskedProperties() {
        logger.info("Fetching application properties...");
        long startTime = System.currentTimeMillis();
        
        Map<String, Object> properties = new TreeMap<>();

        if (environment instanceof ConfigurableEnvironment) {
            ConfigurableEnvironment env = (ConfigurableEnvironment) environment;
            for (PropertySource<?> source : env.getPropertySources()) {
                if (source instanceof EnumerablePropertySource) {
                    EnumerablePropertySource<?> enumerableSource = (EnumerablePropertySource<?>) source;
                    for (String name : enumerableSource.getPropertyNames()) {
                        try {
                            Object value = enumerableSource.getProperty(name);
                            if (isSensitive(name)) {
                                properties.put(name, "******");
                            } else {
                                properties.put(name, value);
                            }
                        } catch (Exception e) {
                            properties.put(name, "[Error reading property: " + e.getMessage() + "]");
                        }
                    }
                }
            }
        } else {
            properties.put("error", "Environment is not an instance of ConfigurableEnvironment");
        }

        long duration = System.currentTimeMillis() - startTime;
        logger.info("Application properties fetched: {} properties in {}ms", properties.size(), duration);
        
        return properties;
    }

    private boolean isSensitive(String name) {
        if (name == null) {
            return false;
        }
        String lowerName = name.toLowerCase();
        for (String keyword : SENSITIVE_KEYWORDS) {
            if (lowerName.contains(keyword)) {
                return true;
            }
        }
        return false;
    }
}
