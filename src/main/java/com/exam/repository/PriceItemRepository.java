package com.exam.repository;

import com.exam.model.fees.PriceItem;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface PriceItemRepository extends JpaRepository<PriceItem, Long> {

    List<PriceItem> findAllByOrderByActiveDescCategoryAscNameAsc();

    Optional<PriceItem> findByNameKey(String nameKey);

    /** Row lock: every change to an item's stock goes through it, so the count can't go wrong or below zero. */
    @org.springframework.data.jpa.repository.Lock(jakarta.persistence.LockModeType.PESSIMISTIC_WRITE)
    @org.springframework.data.jpa.repository.Query("SELECT i FROM PriceItem i WHERE i.id = :id")
    Optional<PriceItem> lockById(@org.springframework.data.repository.query.Param("id") Long id);

    List<PriceItem> findByStockedTrueOrderByNameAsc();
}
