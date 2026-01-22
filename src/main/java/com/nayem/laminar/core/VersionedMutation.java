package com.nayem.laminar.core;

/**
 * Optional interface for mutations that support versioning.
 * <p>
 * Implementing this interface allows Laminar to:
 * <ul>
 * <li>Track mutation schema versions for safe deserialization</li>
 * <li>Detect incompatible versions during cluster communication</li>
 * <li>Enable zero-downtime schema migrations</li>
 * </ul>
 * </p>
 * 
 * <p><strong>Example:</strong></p>
 * <pre>
 * public class AddXpMutation implements Mutation&lt;User&gt;, VersionedMutation {
 *     private String userId;
 *     private long amount;
 *     
 *     &#64;Override
 *     public int getVersion() {
 *         return 1; // Increment when schema changes
 *     }
 *     
 *     // ... other methods
 * }
 * </pre>
 * 
 * @see Mutation
 */
public interface VersionedMutation {
    /**
     * Returns the schema version of this mutation.
     * <p>
     * Increment this value when making breaking changes to:
     * <ul>
     * <li>Field types (e.g., int → long)</li>
     * <li>Field names (e.g., amount → amountInCents)</li>
     * <li>Removing fields</li>
     * <li>Changing coalesce() logic</li>
     * </ul>
     * </p>
     * 
     * @return The version number (e.g., 1, 2, 3...)
     */
    int getVersion();
}
