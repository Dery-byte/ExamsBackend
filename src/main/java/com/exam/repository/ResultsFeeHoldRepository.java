package com.exam.repository;

import com.exam.model.fees.ResultsFeeHold;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import java.util.List;
import java.util.Optional;

public interface ResultsFeeHoldRepository extends JpaRepository<ResultsFeeHold, Long> {

    Optional<ResultsFeeHold> findByProgram_Id(Long programId);

    @Query("SELECT h FROM ResultsFeeHold h JOIN FETCH h.program")
    List<ResultsFeeHold> findAllWithProgram();
}
