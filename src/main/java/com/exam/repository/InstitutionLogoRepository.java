package com.exam.repository;

import com.exam.model.academic.InstitutionLogo;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDateTime;
import java.util.List;

public interface InstitutionLogoRepository extends JpaRepository<InstitutionLogo, Long> {

    /** Just the timestamp, so the institution info can version the logo URL without loading the image. */
    @Query("select l.updatedAt from InstitutionLogo l where l.id = :id")
    List<LocalDateTime> findUpdatedAt(@Param("id") Long id);
}
