package com.exam.repository;

import com.exam.model.Role;
import com.exam.model.User;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
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

    long countByRole(Role role);

    List<User> findByProgramAndCurrentLevel(com.exam.model.exam.Program program, Integer currentLevel);

    // Or for multiple roles at once
    List<User> findByRoleIn(List<String> roles);

    long countByRoleIn(List<String> roles);

//    List<User> findByUser(User user);
}
