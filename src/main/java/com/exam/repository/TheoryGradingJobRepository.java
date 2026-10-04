package com.exam.repository;

import com.exam.model.exam.TheoryGradingJob;
import com.exam.model.exam.TheoryGradingJob.Status;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.Collection;
import java.util.List;
import java.util.Optional;

@Repository
public interface TheoryGradingJobRepository extends JpaRepository<TheoryGradingJob, Long> {

    @Query("SELECT j FROM TheoryGradingJob j WHERE j.status = :status AND j.nextAttemptAt <= :now ORDER BY j.id")
    List<TheoryGradingJob> findDue(@Param("status") Status status, @Param("now") LocalDateTime now, Pageable page);

    Optional<TheoryGradingJob> findFirstByAttemptIdAndStatusIn(Long attemptId, Collection<Status> statuses);

    List<TheoryGradingJob> findByQuizIdAndStatusInOrderByIdAsc(Long quizId, Collection<Status> statuses);

    /** Takes a pending job for this worker. Returns 1 only for the one caller that got it (safe across server instances). */
    @Modifying
    @Transactional
    @Query("UPDATE TheoryGradingJob j SET j.status = :running, j.updatedAt = :now WHERE j.id = :id AND j.status = :pending")
    int claim(@Param("id") Long id, @Param("now") LocalDateTime now,
              @Param("pending") Status pending, @Param("running") Status running);

    default int claim(Long id, LocalDateTime now) {
        return claim(id, now, Status.PENDING, Status.RUNNING);
    }

    /** Jobs left RUNNING by a server that stopped mid-way go back in the queue. */
    @Modifying
    @Transactional
    @Query("UPDATE TheoryGradingJob j SET j.status = :pending, j.nextAttemptAt = :now WHERE j.status = :running AND j.updatedAt < :cutoff")
    int requeueStuck(@Param("cutoff") LocalDateTime cutoff, @Param("now") LocalDateTime now,
                     @Param("pending") Status pending, @Param("running") Status running);

    default int requeueStuck(LocalDateTime cutoff, LocalDateTime now) {
        return requeueStuck(cutoff, now, Status.PENDING, Status.RUNNING);
    }
}
