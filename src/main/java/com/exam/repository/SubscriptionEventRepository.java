package com.exam.repository;

import com.exam.model.monitoring.SubscriptionEvent;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface SubscriptionEventRepository extends JpaRepository<SubscriptionEvent, Long> {

    List<SubscriptionEvent> findTop50ByOrderByAtDescIdDesc();
}
