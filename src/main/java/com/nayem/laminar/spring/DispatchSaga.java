package com.nayem.laminar.spring;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Marks a method as dispatching a Saga transaction.
 * <p>
 * The method must return a {@link com.nayem.laminar.saga.LaminarSaga} instance.
 * The saga will be executed asynchronously by the SagaOrchestrator.
 * </p>
 *
 * <h3>Usage Example</h3>
 * 
 * <pre>
 * {@code
 * @DispatchSaga
 * public LaminarSaga<Account> transferFunds(String from, String to, BigDecimal amount) {
 *     return new TransferSaga(from, to, amount);
 * }
 * }
 * </pre>
 */
@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
public @interface DispatchSaga {
}
