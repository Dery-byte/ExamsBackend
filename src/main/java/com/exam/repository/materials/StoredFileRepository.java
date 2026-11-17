package com.exam.repository.materials;

import com.exam.model.materials.StoredFile;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Collection;
import java.util.List;

public interface StoredFileRepository extends JpaRepository<StoredFile, String> {

    List<StoredFile> findByMaterial_IdOrderByCreatedAt(Long materialId);

    @Query("SELECT f FROM StoredFile f WHERE f.material.id IN :ids ORDER BY f.createdAt")
    List<StoredFile> forMaterials(@Param("ids") Collection<Long> materialIds);

    List<StoredFile> findBySubmission_IdOrderByCreatedAt(Long submissionId);

    @Query("SELECT f FROM StoredFile f WHERE f.submission.id IN :ids ORDER BY f.createdAt")
    List<StoredFile> forSubmissions(@Param("ids") Collection<Long> submissionIds);

    long countBySha256(String sha256);

    /** Each stored content once (sha, size), for the storage total. */
    @Query("SELECT f.sha256, MAX(f.sizeBytes) FROM StoredFile f GROUP BY f.sha256")
    List<Object[]> distinctContents();
}
