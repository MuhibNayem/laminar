package com.nayem.laminar.spring;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Marks a method as a Laminar Dispatch point.
 * <p>
 * The method MUST return a {@link com.nayem.laminar.core.Mutation}.
 * When called, instead of returning the Mutation, Laminar will intercept it,
 * dispatch it to the engine, and return the CompletableFuture (if the return
 * type matches)
 * or block until completion.
 * </p>
 */
@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
public @interface Dispatch {
}
