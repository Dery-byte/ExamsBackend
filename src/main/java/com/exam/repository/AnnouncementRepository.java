package com.exam.repository;

import com.exam.model.comms.Announcement;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface AnnouncementRepository extends JpaRepository<Announcement, Long> {
    List<Announcement> findAllByOrderByPinnedDescCreatedAtDesc();
}
