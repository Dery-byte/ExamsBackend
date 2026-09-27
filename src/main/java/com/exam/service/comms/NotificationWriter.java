package com.exam.service.comms;

import com.exam.model.User;
import com.exam.model.comms.Notification;
import com.exam.repository.NotificationRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.util.Collection;
import java.util.List;

/**
 * Persists notifications in their own transaction, so a failure here can never roll back
 * the business operation (publishing a quiz, approving a sheet …) that triggered it.
 * Use {@link NotificationService}, which also swallows and logs errors.
 */
@Component
public class NotificationWriter {

    @Autowired
    private NotificationRepository notificationRepository;

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void write(Collection<User> recipients, String type, String title, String message, String link) {
        List<Notification> batch = recipients.stream().map(u -> {
            Notification n = new Notification();
            n.setRecipient(u);
            n.setType(type);
            n.setTitle(title);
            n.setMessage(message);
            n.setLink(link);
            return n;
        }).toList();
        notificationRepository.saveAll(batch);
    }
}
