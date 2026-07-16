package com.exam.repository;

import com.exam.model.exam.StudentSectionMark;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

@Repository
public interface StudentSectionMarkRepository extends JpaRepository<StudentSectionMark, Long> {
}
