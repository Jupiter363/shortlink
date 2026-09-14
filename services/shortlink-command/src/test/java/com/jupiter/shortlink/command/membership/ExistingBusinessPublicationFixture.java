package com.jupiter.shortlink.command.membership;

import com.jupiter.shortlink.membership.RegistrationPermit;
import com.jupiter.shortlink.membership.RouteAddress;

import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.function.Supplier;

/** Explicit test injection for pre-membership business regression suites only. */
public final class ExistingBusinessPublicationFixture implements RoutePublication {
    @Override
    public <T> CompletableFuture<T> publish(Supplier<Prepared<T>> preparation) {
        try {
            return CompletableFuture.completedFuture(preparation.get().commit().apply(null));
        } catch (Exception failure) {
            return CompletableFuture.failedFuture(failure);
        }
    }

    @Override
    public void verify(RegistrationPermit permit, List<RouteAddress> addresses) {}

    @Override
    public void assertOpen(RegistrationPermit permit) {}
}
