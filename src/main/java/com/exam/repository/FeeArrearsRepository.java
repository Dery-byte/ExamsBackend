package com.exam.repository;

import com.exam.model.fees.FeeArrears;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Collection;
import java.util.List;

public interface FeeArrearsRepository extends JpaRepository<FeeArrears, Long> {

    /** The student's arrears, oldest first: opening balances, then by session. */
    @Query("SELECT a FROM FeeArrears a LEFT JOIN FETCH a.session s WHERE a.student.id = :studentId "
            + "ORDER BY CASE WHEN s.id IS NULL THEN 0 ELSE 1 END, s.startDate, a.id")
    List<FeeArrears> findForStudent(@Param("studentId") Long studentId);

    @Query("SELECT a FROM FeeArrears a WHERE a.session.id = :sessionId")
    List<FeeArrears> findBySession(@Param("sessionId") Long sessionId);

    @Query("SELECT a FROM FeeArrears a JOIN FETCH a.student s LEFT JOIN FETCH s.program LEFT JOIN FETCH a.session ORDER BY a.createdAt DESC")
    List<FeeArrears> findAllWithStudent();

    @Query("SELECT a FROM FeeArrears a WHERE a.student.id IN :ids AND a.kind = :kind")
    List<FeeArrears> findByStudentsAndKind(@Param("ids") Collection<Long> ids, @Param("kind") FeeArrears.Kind kind);

    @Query("SELECT a.student.id, SUM(a.amount) FROM FeeArrears a WHERE a.status = :status GROUP BY a.student.id")
    List<Object[]> sumByStudent(@Param("status") FeeArrears.Status status);

    /** [studentId, total of their open arrears] for every student with some. */
    default List<Object[]> openByStudent() {
        return sumByStudent(FeeArrears.Status.OPEN);
    }
}
