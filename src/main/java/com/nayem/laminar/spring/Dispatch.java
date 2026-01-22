package com.nayem.laminar.spring;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Marks a method as a Laminar Dispatch point.
 * <p>
 * Methods annotated with {@code @Dispatch} act as entry points to the Laminar
 * engine.
 * The method logic itself is NOT executed immediately. Instead, the returned
 * {@link com.nayem.laminar.core.Mutation}
 * is intercepted and dispatched to the engine for coalescing.
 * </p>
 *
 * <h3>Requirements</h3>
 * <ul>
 * <li>The method MUST return a {@link com.nayem.laminar.core.Mutation}
 * implementation.</li>
 * <li>The method usage depends on whether you want a fire-and-forget or a
 * blocking result.</li>
 * </ul>
 *
 * <h3>Usage Example</h3>
 * 
 * <pre>
 * {
 *     &#64;code
 *     &#64;Service
 *     public class GameService {
 *
 *         @Dispatch
 *         public Mutation<User> addXp(String userId, int amount) {
 *             // This logic creates the mutation but does NOT apply it yet.
 *             return new XpMutation(userId, amount);
 *         }
 *     }
 * }
 * </pre>
 *
 * <h3>Return Values</h3>
 * Although the method signature returns a {@code Mutation}, Laminar's AOP
 * interceptor can
 * return other types to the caller at runtime, such as
 * {@link java.util.concurrent.CompletableFuture}
 * if the method signature is adjusted to match the aspect's behavior (advanced
 * usage).
 * For most standard cases, the caller receives {@code null} (fire-and-forget).
 */
@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
public @interface Dispatch {
}
