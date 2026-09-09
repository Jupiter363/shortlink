package com.jupiter.shortlink.admin.account;

import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;

class AccountRecoveryWorkerTest {
    @Test
    void acknowledgementLossRetriesSameCommandAndDoesNotMarkReady() {
        AccountIntentRepository intents = mock(AccountIntentRepository.class);
        DefaultGroupCommandClient command = mock(DefaultGroupCommandClient.class);
        AccountInitialization item =
                new AccountInitialization(
                        7, "alice", "account-default-group:7", "PENDING", null, 0, 0, 0, null);
        when(intents.claim(eq(item), anyLong(), anyLong())).thenReturn(true);
        when(command.initialize(item)).thenThrow(new IllegalStateException("ACK lost"));
        AccountRecoveryWorker worker =
                new AccountRecoveryWorker(
                        intents,
                        command,
                        mock(AccountSessionStore.class),
                        Clock.fixed(Instant.ofEpochMilli(10_000), ZoneOffset.UTC));
        worker.recover(item);
        verify(intents).failed(item, "COMMAND_UNAVAILABLE", 12_000, 10_000);
        verify(intents, never()).complete(any(), anyString(), anyLong());
    }

    @Test
    void competingWorkerCannotInvokeCommandWithoutClaim() {
        AccountIntentRepository intents = mock(AccountIntentRepository.class);
        DefaultGroupCommandClient command = mock(DefaultGroupCommandClient.class);
        AccountInitialization item =
                new AccountInitialization(
                        7, "alice", "account-default-group:7", "PENDING", null, 0, 0, 0, null);
        new AccountRecoveryWorker(intents, command, mock(AccountSessionStore.class)).recover(item);
        verifyNoInteractions(command);
    }

    @Test
    void redisFailureLeavesRevocationIntentDurableButDoesNotStopInitializationScan() {
        AccountIntentRepository intents = mock(AccountIntentRepository.class);
        AccountSessionStore sessions = mock(AccountSessionStore.class);
        var cleanup = new AccountIntentRepository.SessionCleanup(7, 2, "alice");
        when(intents.findCleanup(64)).thenReturn(List.of(cleanup));
        doThrow(new IllegalStateException("redis unavailable"))
                .when(sessions)
                .revokeBefore("alice", 2);
        when(intents.findDue(anyLong(), eq(8))).thenReturn(List.of());
        new AccountRecoveryWorker(intents, mock(DefaultGroupCommandClient.class), sessions)
                .recover();
        verify(intents, never()).completeCleanup(any());
        verify(intents).findDue(anyLong(), eq(8));
    }
}
