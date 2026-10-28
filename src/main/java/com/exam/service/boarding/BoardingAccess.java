package com.exam.service.boarding;

import com.exam.model.Role;
import com.exam.model.User;
import com.exam.model.boarding.BoardingStaff;
import com.exam.repository.BoardingStaffRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Who may do what in boarding:
 *  - the Super Admin and HEAD boarding staff: every hostel, its structure, staff and settings;
 *  - HOUSE staff: their own hostel's students (give rooms, move within it, vacate);
 *  - any boarding staff: find where a student sleeps (for emergencies and visitors).
 */
@Service
public class BoardingAccess {

    @Autowired private BoardingStaffRepository staffRepository;

    public List<BoardingStaff> dutiesOf(User u) {
        if (u == null || u.getId() == null) return List.of();
        return staffRepository.findForUser(u.getId());
    }

    public boolean isHead(User u) {
        if (u == null) return false;
        if (u.getRole() == Role.SUPER_ADMIN) return true;
        return dutiesOf(u).stream().anyMatch(d -> d.getDuty() == BoardingStaff.Duty.HEAD);
    }

    /** Anyone who works in boarding (sees the Boarding pages). */
    public boolean isBoardingStaff(User u) {
        if (u == null) return false;
        if (u.getRole() == Role.SUPER_ADMIN) return true;
        return (u.getRole() == Role.ADMIN || u.getRole() == Role.LECTURER) && !dutiesOf(u).isEmpty();
    }

    /** The hostels a HOUSE member looks after (ignored for heads, who manage all). */
    public Set<Long> houseHostelIds(User u) {
        return dutiesOf(u).stream().filter(d -> d.getDuty() == BoardingStaff.Duty.HOUSE && d.getHostel() != null)
                .map(d -> d.getHostel().getId()).collect(Collectors.toSet());
    }

    public boolean canManageHostel(User u, Long hostelId) {
        if (isHead(u)) return true;
        return hostelId != null && houseHostelIds(u).contains(hostelId);
    }

    public void requireStaff(User u) {
        if (!isBoardingStaff(u)) throw new AccessDeniedException("You have no boarding duties. Ask the Super Admin to add you as boarding staff.");
    }

    public void requireHead(User u) {
        if (!isHead(u)) throw new AccessDeniedException("Only the Super Admin or the head of boarding can do this.");
    }

    public void requireHostel(User u, Long hostelId) {
        if (!canManageHostel(u, hostelId)) throw new AccessDeniedException("You don't look after this hostel.");
    }
}
