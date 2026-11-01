package com.exam.service.comms;

import com.exam.model.User;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.util.List;

import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** Inside a transaction notifications wait for the commit: written earlier, they hung on rows the caller had locked. */
@ExtendWith(MockitoExtension.class)
class NotificationServiceTest {

    @Mock NotificationWriter writer;
    @InjectMocks NotificationService service;

    @AfterEach
    void endTransaction() {
        if (TransactionSynchronizationManager.isSynchronizationActive()) TransactionSynchronizationManager.clearSynchronization();
        TransactionSynchronizationManager.setActualTransactionActive(false);
    }

    private static User user(long id) {
        User u = new User();
        u.setId(id);
        return u;
    }

    @Test
    void writesStraightAwayOutsideATransaction() {
        service.notify(user(1), "T", "Title", "Message", "/x");
        verify(writer).write(anyCollection(), eq("T"), eq("Title"), eq("Message"), eq("/x"));
    }

    @Test
    void waitsForTheCommit() {
        TransactionSynchronizationManager.initSynchronization();
        TransactionSynchronizationManager.setActualTransactionActive(true);

        service.notify(List.of(user(1), user(2), user(1)), "T", "Title", "Message", "/x");
        verifyNoInteractions(writer);

        TransactionSynchronizationManager.getSynchronizations().forEach(TransactionSynchronization::afterCommit);
        verify(writer).write(argThat(c -> c.size() == 2), eq("T"), eq("Title"), eq("Message"), eq("/x"));
    }

    @Test
    void nothingWhenTheTransactionRollsBack() {
        TransactionSynchronizationManager.initSynchronization();
        TransactionSynchronizationManager.setActualTransactionActive(true);

        service.notify(user(1), "T", "Title", "Message", "/x");
        TransactionSynchronizationManager.getSynchronizations()
                .forEach(s -> s.afterCompletion(TransactionSynchronization.STATUS_ROLLED_BACK));
        verifyNoInteractions(writer);
    }
}
