package com.example.mcp.tools;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationContext;

import java.lang.annotation.Annotation;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.*;

/**
 * Scans JPA @Entity classes and shows table mappings, field definitions, column details,
 * and relationship annotations. Returns gracefully if JPA is not on the classpath.
 */
public class JpaEntityInfoTool {

    private static final Logger logger = LoggerFactory.getLogger(JpaEntityInfoTool.class);

    private final ApplicationContext applicationContext;

    public JpaEntityInfoTool(ApplicationContext applicationContext) {
        this.applicationContext = applicationContext;
    }

    public Map<String, Object> getJpaEntityInfo() {
        Map<String, Object> result = new LinkedHashMap<>();

        try {
            Class.forName("jakarta.persistence.Entity");
        } catch (ClassNotFoundException e) {
            result.put("available", false);
            result.put("message", "JPA is not available on the classpath. Entity scanning skipped.");
            return result;
        }

        logger.info("Scanning JPA entities...");
        long startTime = System.currentTimeMillis();

        try {
            Class<? extends Annotation> entityAnnotation = loadAnnotation("jakarta.persistence.Entity");
            Map<String, Object> entities = applicationContext.getBeansWithAnnotation(entityAnnotation);

            List<Map<String, Object>> entityList = new ArrayList<>();

            for (Map.Entry<String, Object> entry : entities.entrySet()) {
                String beanName = entry.getKey();
                Class<?> entityClass = entry.getValue().getClass();

                // Skip proxy classes
                if (entityClass.getName().contains("$$")) {
                    try {
                        entityClass = entityClass.getSuperclass();
                    } catch (Exception ignored) {}
                }

                Map<String, Object> entityInfo = new LinkedHashMap<>();
                entityInfo.put("entityClass", entityClass.getName());
                entityInfo.put("beanName", beanName);

                // @Table annotation
                String tableName = getTableName(entityClass);
                entityInfo.put("tableName", tableName);

                // Fields
                entityInfo.put("fields", getEntityFields(entityClass));

                // Relationships
                entityInfo.put("relationships", getEntityRelationships(entityClass));

                entityList.add(entityInfo);
            }

            // Sort by entity class name
            entityList.sort(Comparator.comparing(e -> (String) e.get("entityClass")));

            result.put("available", true);
            result.put("entityCount", entityList.size());
            result.put("entities", entityList);

        } catch (Exception e) {
            logger.warn("Failed to scan JPA entities: {}", e.getMessage());
            result.put("available", true);
            result.put("error", e.getMessage());
            result.put("entityCount", 0);
            result.put("entities", Collections.emptyList());
        }

        long duration = System.currentTimeMillis() - startTime;
        logger.info("JPA entity scan completed in {}ms", duration);

        return result;
    }

    private String getTableName(Class<?> entityClass) {
        try {
            Class<? extends Annotation> tableAnnotation = loadAnnotation("jakarta.persistence.Table");
            Annotation table = entityClass.getAnnotation(tableAnnotation);
            if (table != null) {
                Method nameMethod = tableAnnotation.getMethod("name");
                String name = (String) nameMethod.invoke(table);
                if (name != null && !name.isEmpty()) {
                    return name;
                }
            }
        } catch (Exception ignored) {}

        // Default: unqualified class name
        String simpleName = entityClass.getSimpleName();
        // Hibernate default: camelCase to snake_case
        return camelToSnake(simpleName);
    }

    private List<Map<String, Object>> getEntityFields(Class<?> entityClass) {
        List<Map<String, Object>> fields = new ArrayList<>();
        Class<? extends Annotation> columnAnnotation;
        Class<? extends Annotation> idAnnotation;
        Class<? extends Annotation> generatedValueAnnotation;

        try {
            columnAnnotation = loadAnnotation("jakarta.persistence.Column");
            idAnnotation = loadAnnotation("jakarta.persistence.Id");
            generatedValueAnnotation = loadAnnotation("jakarta.persistence.GeneratedValue");
        } catch (Exception e) {
            return fields;
        }

        for (Field field : entityClass.getDeclaredFields()) {
            Map<String, Object> fieldInfo = new LinkedHashMap<>();
            fieldInfo.put("name", field.getName());
            fieldInfo.put("type", field.getType().getSimpleName());

            // @Id
            boolean isId = field.isAnnotationPresent(idAnnotation);
            fieldInfo.put("isId", isId);

            // @GeneratedValue
            if (isId) {
                fieldInfo.put("isGeneratedValue", field.isAnnotationPresent(generatedValueAnnotation));
            }

            // @Column
            Annotation col = field.getAnnotation(columnAnnotation);
            if (col != null) {
                try {
                    Method nameMethod = columnAnnotation.getMethod("name");
                    String colName = (String) nameMethod.invoke(col);
                    if (colName != null && !colName.isEmpty()) {
                        fieldInfo.put("columnName", colName);
                    } else {
                        fieldInfo.put("columnName", camelToSnake(field.getName()));
                    }

                    Method nullableMethod = columnAnnotation.getMethod("nullable");
                    fieldInfo.put("isNullable", (boolean) nullableMethod.invoke(col));

                    Method uniqueMethod = columnAnnotation.getMethod("unique");
                    boolean unique = (boolean) uniqueMethod.invoke(col);
                    if (unique) fieldInfo.put("isUnique", true);

                    Method lengthMethod = columnAnnotation.getMethod("length");
                    int length = (int) lengthMethod.invoke(col);
                    if (length != 255) fieldInfo.put("length", length);
                } catch (Exception ignored) {}
            } else {
                fieldInfo.put("columnName", camelToSnake(field.getName()));
            }

            fields.add(fieldInfo);
        }

        return fields;
    }

    private List<Map<String, Object>> getEntityRelationships(Class<?> entityClass) {
        List<Map<String, Object>> relationships = new ArrayList<>();

        String[] relAnnotationNames = {
            "jakarta.persistence.OneToOne",
            "jakarta.persistence.OneToMany",
            "jakarta.persistence.ManyToOne",
            "jakarta.persistence.ManyToMany"
        };

        for (String relAnnotationName : relAnnotationNames) {
            try {
                Class<? extends Annotation> relAnnotation = loadAnnotation(relAnnotationName);
                for (Field field : entityClass.getDeclaredFields()) {
                    Annotation rel = field.getAnnotation(relAnnotation);
                    if (rel != null) {
                        Map<String, Object> relInfo = new LinkedHashMap<>();
                        relInfo.put("type", relAnnotation.getSimpleName().toUpperCase());
                        relInfo.put("fieldName", field.getName());

                        // targetEntity
                        try {
                            Method targetMethod = relAnnotation.getMethod("targetEntity");
                            Class<?> target = (Class<?>) targetMethod.invoke(rel);
                            if (target != null && !target.equals(void.class) && !target.equals(Object.class)) {
                                relInfo.put("targetEntity", target.getSimpleName());
                            }
                        } catch (Exception ignored) {}

                        // If no explicit targetEntity, try generic type
                        if (!relInfo.containsKey("targetEntity")) {
                            try {
                                if (field.getGenericType() instanceof java.lang.reflect.ParameterizedType) {
                                    java.lang.reflect.ParameterizedType pt = (java.lang.reflect.ParameterizedType) field.getGenericType();
                                    java.lang.reflect.Type[] args = pt.getActualTypeArguments();
                                    if (args.length > 0 && args[0] instanceof Class) {
                                        relInfo.put("targetEntity", ((Class<?>) args[0]).getSimpleName());
                                    }
                                } else if (field.getType() != null) {
                                    relInfo.put("targetEntity", field.getType().getSimpleName());
                                }
                            } catch (Exception ignored) {}
                        }

                        // mappedBy
                        try {
                            Method mappedByMethod = relAnnotation.getMethod("mappedBy");
                            String mappedBy = (String) mappedByMethod.invoke(rel);
                            if (mappedBy != null && !mappedBy.isEmpty()) {
                                relInfo.put("mappedBy", mappedBy);
                            }
                        } catch (Exception ignored) {}

                        // @JoinColumn
                        try {
                            Class<? extends Annotation> joinColAnnotation = loadAnnotation("jakarta.persistence.JoinColumn");
                            Annotation joinCol = field.getAnnotation(joinColAnnotation);
                            if (joinCol != null) {
                                Method joinNameMethod = joinColAnnotation.getMethod("name");
                                String joinName = (String) joinNameMethod.invoke(joinCol);
                                if (joinName != null && !joinName.isEmpty()) {
                                    relInfo.put("joinColumn", joinName);
                                }
                            }
                        } catch (Exception ignored) {}

                        relationships.add(relInfo);
                    }
                }
            } catch (ClassNotFoundException e) {
                // JPA annotation not available, skip
            }
        }

        return relationships;
    }

    private String camelToSnake(String camel) {
        if (camel == null || camel.isEmpty()) return camel;
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < camel.length(); i++) {
            char c = camel.charAt(i);
            if (Character.isUpperCase(c)) {
                if (i > 0) sb.append('_');
                sb.append(Character.toLowerCase(c));
            } else {
                sb.append(c);
            }
        }
        return sb.toString();
    }

    @SuppressWarnings("unchecked")
    private Class<? extends Annotation> loadAnnotation(String className) throws ClassNotFoundException {
        return (Class<? extends Annotation>) Class.forName(className);
    }
}
