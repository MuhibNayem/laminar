package com.nayem.laminar.migration;

import com.nayem.laminar.core.Mutation;
import com.nayem.laminar.core.VersionedMutation;

import java.util.HashMap;
import java.util.Map;
import java.util.function.Function;

/**
 * Migration utility for handling schema evolution in Laminar mutations.
 * <p>
 * Provides tools for safely migrating mutation schemas across versions without
 * breaking existing clients or requiring coordinated deployments. Supports both
 * forward and backward compatibility strategies.
 * </p>
 * 
 * <h2>Migration Strategies</h2>
 * 
 * <h3>1. Version Detection & Routing</h3>
 * <pre>{@code
 * MutationMigrator migrator = new MutationMigrator();
 * migrator.registerTransformer(1, 2, oldMutation -> {
 *     // Transform v1 mutation to v2
 *     return new XpMutationV2(oldMutation.getUserId(), oldMutation.getAmount(), "migrated");
 * });
 * 
 * Mutation<?> migrated = migrator.migrate(incomingMutation, 2);
 * }</pre>
 * 
 * <h3>2. Default Value Injection</h3>
 * <pre>{@code
 * migrator.registerDefaultValue("reason", "system_migrated");
 * }</pre>
 * 
 * <h3>3. Field Deprecation</h3>
 * <pre>{@code
 * migrator.deprecateField("legacyField", "2.0", "Use 'newField' instead");
 * }</pre>
 * 
 * <h2>Best Practices</h2>
 * <ul>
 *   <li><b>Additive Changes Only</b>: New fields should be optional with defaults</li>
 *   <li><b>Never Remove Fields</b>: Mark as deprecated, remove after 2+ major versions</li>
 *   <li><b>Test All Paths</b>: Verify migrations work in both directions</li>
 *   <li><b>Monitor Migration Rates</b>: Track how many mutations require transformation</li>
 *   <li><b>Document Breaking Changes</b>: Provide clear migration guides</li>
 * </ul>
 * 
 * <h2>Version Numbering</h2>
 * <p>
 * Follow semantic versioning: MAJOR.MINOR.PATCH
 * </p>
 * <ul>
 *   <li>MAJOR: Breaking changes requiring migration</li>
 *   <li>MINOR: Backward-compatible additions</li>
 *   <li>PATCH: Bug fixes, no schema changes</li>
 * </ul>
 */
public class MutationMigrator {
    
    private final Map<String, FieldDeprecation> deprecatedFields = new HashMap<>();
    private final Map<String, Object> defaultValues = new HashMap<>();
    private final java.util.NavigableMap<Integer, Function<Mutation<?>, Mutation<?>>> transformers = 
        new java.util.TreeMap<>();
    private int currentVersion = 1;
    
    /**
     * Registers a transformer function to migrate mutations from one version to the next.
     *
     * @param fromVersion the source version
     * @param toVersion the target version (must be fromVersion + 1)
     * @param transformer function that transforms a mutation from old to new schema
     * @throws IllegalArgumentException if version numbers are invalid
     */
    public void registerTransformer(int fromVersion, int toVersion, 
                                     Function<Mutation<?>, Mutation<?>> transformer) {
        if (toVersion != fromVersion + 1) {
            throw new IllegalArgumentException(
                "Transformers must be registered for consecutive versions only: " +
                fromVersion + " -> " + toVersion);
        }
        
        transformers.put(fromVersion, transformer);
        currentVersion = Math.max(currentVersion, toVersion);
    }
    
    /**
     * Migrates a mutation to the specified target version.
     * <p>
     * Applies all necessary transformers in sequence to upgrade the mutation
     * from its current version to the target version.
     * </p>
     *
     * @param mutation the mutation to migrate
     * @param targetVersion the desired version
     * @return the migrated mutation
     * @throws MigrationException if no transformer chain exists
     */
    @SuppressWarnings("unchecked")
    public <T extends Mutation<?>> T migrate(T mutation, int targetVersion) {
        if (!(mutation instanceof VersionedMutation vm)) {
            // Non-versioned mutation, assume v1
            if (targetVersion > 1 && !transformers.isEmpty()) {
                return applyTransformers((T) wrapAsVersioned(mutation), 1, targetVersion);
            }
            return mutation;
        }
        
        int currentVersion = vm.getVersion();
        
        if (currentVersion == targetVersion) {
            return mutation;
        }
        
        if (currentVersion > targetVersion) {
            throw new MigrationException(
                "Downgrade not supported: cannot migrate from v" + currentVersion + 
                " to v" + targetVersion);
        }
        
        return applyTransformers(mutation, currentVersion, targetVersion);
    }
    
    /**
     * Gets the current schema version.
     *
     * @return the latest supported version number
     */
    public int getCurrentVersion() {
        return currentVersion;
    }
    
    /**
     * Registers a default value for a field (used when migrating from older versions).
     *
     * @param fieldName the field name
     * @param defaultValue the default value to use
     */
    public void registerDefaultValue(String fieldName, Object defaultValue) {
        defaultValues.put(fieldName, defaultValue);
    }
    
    /**
     * Marks a field as deprecated.
     *
     * @param fieldName the field name
     * @param deprecatedSince version when deprecation started
     * @param message deprecation message with migration guidance
     */
    public void deprecateField(String fieldName, String deprecatedSince, String message) {
        deprecatedFields.put(fieldName, new FieldDeprecation(deprecatedSince, message));
    }
    
    /**
     * Gets deprecation information for a field.
     *
     * @param fieldName the field name
     * @return deprecation info, or null if not deprecated
     */
    public FieldDeprecation getDeprecation(String fieldName) {
        return deprecatedFields.get(fieldName);
    }
    
    /**
     * Gets the default value for a field.
     *
     * @param fieldName the field name
     * @return the default value, or null if none registered
     */
    public Object getDefaultValue(String fieldName) {
        return defaultValues.get(fieldName);
    }
    
    /**
     * Gets migration statistics.
     *
     * @return map containing migration metrics
     */
    public Map<String, Object> getStats() {
        Map<String, Object> stats = new HashMap<>();
        stats.put("currentVersion", currentVersion);
        stats.put("registeredTransformers", transformers.size());
        stats.put("deprecatedFields", deprecatedFields.size());
        stats.put("defaultValues", defaultValues.size());
        stats.put("supportedVersions", java.util.stream.IntStream.rangeClosed(1, currentVersion)
            .boxed()
            .toList());
        return stats;
    }
    
    @SuppressWarnings("unchecked")
    private <T extends Mutation<?>> T applyTransformers(T mutation, int fromVersion, int toVersion) {
        Mutation<?> result = mutation;
        int version = fromVersion;
        
        while (version < toVersion) {
            Function<Mutation<?>, Mutation<?>> transformer = transformers.get(version);
            if (transformer == null) {
                throw new MigrationException(
                    "No transformer registered for version " + version + " -> " + (version + 1));
            }
            result = transformer.apply(result);
            version++;
        }
        
        return (T) result;
    }
    
    private Mutation<?> wrapAsVersioned(Mutation<?> mutation) {
        // Create a wrapper that reports version 1
        return new Mutation<>() {
            @Override
            public String getEntityKey() {
                return mutation.getEntityKey();
            }
            
            @Override
            public Mutation<?> coalesce(Mutation<?> other) {
                return mutation.coalesce(other);
            }
            
            @Override
            public void apply(Object entity) {
                mutation.apply(entity);
            }
        };
    }
    
    /**
     * Exception thrown when migration fails.
     */
    public static class MigrationException extends RuntimeException {
        public MigrationException(String message) {
            super(message);
        }
        
        public MigrationException(String message, Throwable cause) {
            super(message, cause);
        }
    }
    
    /**
     * Holds deprecation information for a field.
     *
     * @param deprecatedSince version when deprecation started
     * @param message deprecation message with migration guidance
     */
    public record FieldDeprecation(String deprecatedSince, String message) {}
}
