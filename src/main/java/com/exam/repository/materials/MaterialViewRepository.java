package com.exam.repository.materials;

import com.exam.model.materials.MaterialView;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

public interface MaterialViewRepository extends JpaRepository<MaterialView, Long> {

    Optional<MaterialView> findByMaterialIdAndStudentId(Long materialId, Long studentId);

    List<MaterialView> findByMaterialId(Long materialId);

    @Query("SELECT v.materialId FROM MaterialView v WHERE v.studentId = :studentId AND v.materialId IN :ids")
    List<Long> openedBy(@Param("studentId") Long studentId, @Param("ids") Collection<Long> materialIds);

    /** materialId → number of students who opened it. */
    @Query("SELECT v.materialId, COUNT(v) FROM MaterialView v WHERE v.materialId IN :ids GROUP BY v.materialId")
    List<Object[]> countsFor(@Param("ids") Collection<Long> materialIds);
}
