package com.exam.repository;

import com.exam.model.Role;
import com.exam.model.User;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface UserRepository extends JpaRepository<User, Long> {

    Optional<User> findByEmail(String email);
    Optional<User> findByUsername(String username);

    /** Row lock used to serialise a single student's attempt start/submit/retake operations. */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT u FROM User u WHERE u.id = :id")
    Optional<User> lockById(@Param("id") Long id);


//    List<User> findByRole(Role role);         // Fetch all lecturers

    Optional<User> findByIdAndRole(Long id, Role role);

    User findByPhone(String phone);

    List<User> findByRole(Role role);

    /** One-time migration: 'enabled' was never enforced before, so every existing account starts active. */
    @Modifying
    @Query("UPDATE User u SET u.enabled = true WHERE u.enabled = false AND u.deactivatedAt IS NULL")
    int enableAllNeverDeactivated();

    /** Name / ID search among accounts with these roles; :q is a lower-case LIKE pattern. */
    @Query("SELECT u FROM User u WHERE u.role IN :roles AND (LOWER(u.username) LIKE :q OR LOWER(u.firstname) LIKE :q"
            + " OR LOWER(u.lastname) LIKE :q OR LOWER(CONCAT(CONCAT(u.firstname, ' '), u.lastname)) LIKE :q) ORDER BY u.firstname, u.lastname")
    List<User> searchByRoles(@Param("roles") java.util.Collection<Role> roles, @Param("q") String q, org.springframework.data.domain.Pageable page);

    /** Boarding: students with no residency recorded get this one (when the institution starts having both). */
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("UPDATE User u SET u.residency = :residency WHERE u.role = com.exam.model.Role.NORMAL AND u.residency IS NULL")
    int fillMissingResidency(@Param("residency") com.exam.model.Residency residency);

    long countByRole(Role role);

    List<User> findByRoleAndUsernameIn(Role role, java.util.Collection<String> usernames);

    /** Active students with their programme, of one programme / level when given (billing a group). */
    @Query("SELECT u FROM User u LEFT JOIN FETCH u.program p WHERE u.role = com.exam.model.Role.NORMAL AND u.enabled = true "
            + "AND (:programId IS NULL OR p.id = :programId) AND (:level IS NULL OR u.currentLevel = :level)")
    List<User> findActiveStudents(@Param("programId") Long programId, @Param("level") Integer level);

    List<User> findByProgramAndCurrentLevel(com.exam.model.exam.Program program, Integer currentLevel);

    // Or for multiple roles at once
    List<User> findByRoleIn(List<String> roles);

    long countByRoleIn(List<String> roles);

    /** [programId, level, number of active students] — what each fee schedule bills. */
    @Query("SELECT u.program.id, u.currentLevel, COUNT(u) FROM User u WHERE u.role = com.exam.model.Role.NORMAL "
            + "AND u.enabled = true AND u.program IS NOT NULL AND u.currentLevel IS NOT NULL GROUP BY u.program.id, u.currentLevel")
    List<Object[]> countActiveStudentsByProgramAndLevel();

    /** Students whose name, username (student ID) or email contains q (q already lower-cased with %). */
    @Query("SELECT u FROM User u WHERE u.role = com.exam.model.Role.NORMAL AND (LOWER(u.username) LIKE :q "
            + "OR LOWER(u.email) LIKE :q OR LOWER(CONCAT(u.firstname, ' ', u.lastname)) LIKE :q) ORDER BY u.firstname, u.lastname")
    List<User> searchStudents(@Param("q") String q, org.springframework.data.domain.Pageable pageable);

//    List<User> findByUser(User user);
}
