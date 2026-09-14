package com.jupiter.shortlink.command.membership;

import com.jupiter.shortlink.membership.RegistrationPermit;
import com.jupiter.shortlink.membership.RouteAddress;

import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.function.Function;
import java.util.function.Supplier;

/** Every production route creation passes this registration and publication barrier. */
public interface RoutePublication {
    record Prepared<T>(List<RouteAddress> addresses, Function<RegistrationPermit, T> commit) {
        public Prepared {
            addresses = List.copyOf(addresses);
            java.util.Objects.requireNonNull(commit);
        }
    }

    <T> CompletableFuture<T> publish(Supplier<Prepared<T>> preparation);

    void verify(RegistrationPermit permit, List<RouteAddress> addresses);

    void assertOpen(RegistrationPermit permit);

    static <T, R> CompletableFuture<R> map(CompletableFuture<T> source, Function<T, R> mapper) {
        CompletableFuture<R> result = new CompletableFuture<>();
        source.whenComplete(
                (value, failure) -> {
                    if (failure != null) result.completeExceptionally(failure);
                    else
                        try {
                            result.complete(mapper.apply(value));
                        } catch (Throwable error) {
                            result.completeExceptionally(error);
                        }
                });
        result.whenComplete(
                (value, failure) -> {
                    if (result.isCancelled()) source.cancel(false);
                });
        return result;
    }
}
