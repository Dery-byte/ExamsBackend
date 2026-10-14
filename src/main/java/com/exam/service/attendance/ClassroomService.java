package com.exam.service.attendance;

import com.exam.model.Role;
import com.exam.model.User;
import com.exam.model.attendance.Classroom;
import com.exam.model.exam.Department;
import com.exam.repository.ClassScheduleRepository;
import com.exam.repository.ClassroomRepository;
import com.exam.repository.DepartmentRepository;
import com.exam.service.comms.CurrentUserService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Saved classrooms. Staff see shared rooms and their department's rooms; a room is changed by
 * whoever saved it, its department's HOD or the Super Admin.
 */
@Service
public class ClassroomService {

    @Autowired private ClassroomRepository classrooms;
    @Autowired private ClassScheduleRepository schedules;
    @Autowired private DepartmentRepository departments;

    public record ClassroomRequest(String name, String building, Long departmentId, Double latitude, Double longitude,
                                   Integer radiusMeters, Integer accuracyMeters) {}

    @Transactional(readOnly = true)
    public List<Map<String, Object>> list(User actor) {
        requireStaff(actor);
        return classrooms.findAllByOrderByNameAsc().stream()
                .filter(c -> actor.getRole() == Role.SUPER_ADMIN || c.getDepartment() == null
                        || AttendanceAccess.sameDepartment(c.getDepartment(), actor.getDepartment()))
                .map(c -> dto(c, actor)).toList();
    }

    @Transactional
    public Map<String, Object> create(User actor, ClassroomRequest req) {
        requireStaff(actor);
        Classroom c = new Classroom();
        c.setCreatedById(actor.getId());
        c.setCreatedByName(CurrentUserService.displayName(actor));
        apply(c, req, actor);
        return dto(classrooms.save(c), actor);
    }

    @Transactional
    public Map<String, Object> update(User actor, Long id, ClassroomRequest req) {
        Classroom c = editable(actor, id);
        apply(c, req, actor);
        return dto(classrooms.save(c), actor);
    }

    @Transactional
    public void delete(User actor, Long id) {
        Classroom c = editable(actor, id);
        schedules.detachClassroom(c.getId());
        classrooms.delete(c);
    }

    private Classroom editable(User actor, Long id) {
        requireStaff(actor);
        Classroom c = classrooms.findById(id).orElseThrow(() -> new IllegalArgumentException("Classroom not found."));
        if (!canEdit(actor, c)) throw new AccessDeniedException("Only whoever saved this room, its HOD or the Super Admin can change it.");
        return c;
    }

    static boolean canEdit(User actor, Classroom c) {
        if (actor.getRole() == Role.SUPER_ADMIN) return true;
        if (actor.getId().equals(c.getCreatedById())) return true;
        return actor.getRole() == Role.ADMIN && AttendanceAccess.sameDepartment(actor.getDepartment(), c.getDepartment());
    }

    private void apply(Classroom c, ClassroomRequest r, User actor) {
        if (r == null) throw new IllegalArgumentException("Nothing to save.");
        String name = r.name() == null ? "" : r.name().trim();
        if (name.isEmpty()) throw new IllegalArgumentException("Give the room a name, e.g. LT 2.");
        if (name.length() > 100) throw new IllegalArgumentException("The room name is too long.");
        String building = r.building() == null || r.building().isBlank() ? null : r.building().trim();
        if (building != null && building.length() > 100) throw new IllegalArgumentException("The building name is too long.");
        if (!AttendanceGeo.validPoint(r.latitude(), r.longitude())) throw new IllegalArgumentException("Measure the room's location first.");
        if (r.accuracyMeters() != null && r.accuracyMeters() > AttendanceService.GUESSED_LOCATION_METERS)
            throw new IllegalArgumentException("That measurement is only a guess from the internet connection. Measure the room with a phone that has location (GPS) on.");
        int radius = r.radiusMeters() == null ? 50 : r.radiusMeters();
        if (radius < 10 || radius > 300) throw new IllegalArgumentException("The allowed distance must be between 10 and 300 metres.");
        Department d;
        if (actor.getRole() == Role.SUPER_ADMIN) {
            d = r.departmentId() == null ? null
                    : departments.findById(r.departmentId()).orElseThrow(() -> new IllegalArgumentException("Department not found."));
        } else {
            d = c.getId() != null ? c.getDepartment() : actor.getDepartment();
        }
        c.setName(name);
        c.setBuilding(building);
        c.setDepartment(d);
        c.setLatitude(r.latitude());
        c.setLongitude(r.longitude());
        c.setRadiusMeters(radius);
        if (r.accuracyMeters() != null) c.setMeasuredAccuracyMeters(Math.max(1, r.accuracyMeters()));
        c.setUpdatedAt(LocalDateTime.now());
    }

    private static void requireStaff(User actor) {
        if (!AttendanceAccess.isStaff(actor)) throw new AccessDeniedException("Staff only.");
    }

    static Map<String, Object> dto(Classroom c, User viewer) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", c.getId());
        m.put("name", c.getName());
        m.put("building", c.getBuilding());
        m.put("departmentId", c.getDepartment() != null ? c.getDepartment().getId() : null);
        m.put("departmentName", c.getDepartment() != null ? c.getDepartment().getName() : null);
        m.put("latitude", c.getLatitude());
        m.put("longitude", c.getLongitude());
        m.put("radiusMeters", c.getRadiusMeters());
        m.put("measuredAccuracyMeters", c.getMeasuredAccuracyMeters());
        m.put("createdBy", c.getCreatedByName());
        m.put("canEdit", viewer != null && canEdit(viewer, c));
        return m;
    }
}
