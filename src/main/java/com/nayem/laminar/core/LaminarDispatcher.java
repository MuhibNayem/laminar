package com.nayem.laminar.core;

import java.util.concurrent.CompletableFuture;

public interface LaminarDispatcher<T> {
    CompletableFuture<Void> dispatch(Mutation<T> mutation);
}
