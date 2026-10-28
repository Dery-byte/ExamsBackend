package com.exam.controller;

import com.exam.exception.ErrorMessage;
import com.exam.model.User;
import com.exam.service.boarding.BoardingAccess;
import com.exam.service.boarding.BoardingBulkService;
import com.exam.service.boarding.BoardingService;
import com.exam.service.boarding.BoardingSettings;
import com.exam.service.boarding.HostelService;
import com.exam.service.comms.CurrentUserService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.web.bind.annotation.*;

import java.util.Map;
import java.util.Optional;

/**
 * Boarding: hostels, blocks, floors, rooms and who lives in each. /student: a student's own room;
 * /manage: boarding staff (who may do what is checked in {@code BoardingAccess}); /settings: wording.
 * While the developer has the institution on "day only", every request is refused with BOARDING_OFF.
 */
@RestController
@RequestMapping("/api/boarding")
public class BoardingController {

    @Autowired private CurrentUserService currentUserService;
    @Autowired private BoardingService boarding;
    @Autowired private HostelService hostels;
    @Autowired private BoardingSettings settings;
    @Autowired private BoardingAccess access;
    @Autowired private BoardingBulkService bulk;

    // ── Student ──────────────────────────────────────────────────────────────

    /** Works in every mode (a day school answers residency DAY) so the dashboard can always ask. */
    @GetMapping("/student/me")
    public ResponseEntity<?> myRoom() {
        return withUser(u -> ResponseEntity.ok(boarding.myRoom(u)));
    }

    // ── Hostels, blocks and rooms ────────────────────────────────────────────

    @GetMapping("/manage/overview")
    public ResponseEntity<?> overview(@RequestParam(defaultValue = "false") boolean archived) {
        return withUser(u -> ResponseEntity.ok(hostels.overview(u, archived)));
    }

    @GetMapping("/manage/hostels/{id}")
    public ResponseEntity<?> hostel(@PathVariable Long id) {
        return withUser(u -> ResponseEntity.ok(hostels.hostel(u, id)));
    }

    @PostMapping("/manage/hostels")
    public ResponseEntity<?> addHostel(@RequestBody HostelService.HostelRequest req) {
        return withUser(u -> ResponseEntity.ok(hostels.saveHostel(u, null, req)));
    }

    @PutMapping("/manage/hostels/{id}")
    public ResponseEntity<?> updateHostel(@PathVariable Long id, @RequestBody HostelService.HostelRequest req) {
        return withUser(u -> ResponseEntity.ok(hostels.saveHostel(u, id, req)));
    }

    @DeleteMapping("/manage/hostels/{id}")
    public ResponseEntity<?> deleteHostel(@PathVariable Long id) {
        return withUser(u -> ResponseEntity.ok(hostels.deleteHostel(u, id)));
    }

    @PostMapping("/manage/hostels/{id}/restore")
    public ResponseEntity<?> restoreHostel(@PathVariable Long id) {
        return withUser(u -> ResponseEntity.ok(hostels.restoreHostel(u, id)));
    }

    @PostMapping("/manage/hostels/{id}/blocks")
    public ResponseEntity<?> addBlock(@PathVariable Long id, @RequestBody HostelService.BlockRequest req) {
        return withUser(u -> ResponseEntity.ok(hostels.saveBlock(u, id, null, req)));
    }

    @PutMapping("/manage/blocks/{id}")
    public ResponseEntity<?> updateBlock(@PathVariable Long id, @RequestBody HostelService.BlockRequest req) {
        return withUser(u -> ResponseEntity.ok(hostels.saveBlock(u, null, id, req)));
    }

    @DeleteMapping("/manage/blocks/{id}")
    public ResponseEntity<?> deleteBlock(@PathVariable Long id) {
        return withUser(u -> ResponseEntity.ok(hostels.deleteBlock(u, id)));
    }

    @PostMapping("/manage/hostels/{id}/rooms")
    public ResponseEntity<?> addRoom(@PathVariable Long id, @RequestBody HostelService.RoomRequest req) {
        return withUser(u -> ResponseEntity.ok(hostels.saveRoom(u, id, null, req)));
    }

    @PostMapping("/manage/hostels/{id}/rooms/generate")
    public ResponseEntity<?> generateRooms(@PathVariable Long id, @RequestBody HostelService.GenerateRequest req) {
        return withUser(u -> ResponseEntity.ok(hostels.generateRooms(u, id, req)));
    }

    @PutMapping("/manage/rooms/{id}")
    public ResponseEntity<?> updateRoom(@PathVariable Long id, @RequestBody HostelService.RoomRequest req) {
        return withUser(u -> ResponseEntity.ok(hostels.saveRoom(u, null, id, req)));
    }

    @DeleteMapping("/manage/rooms/{id}")
    public ResponseEntity<?> deleteRoom(@PathVariable Long id) {
        return withUser(u -> ResponseEntity.ok(hostels.deleteRoom(u, id)));
    }

    @GetMapping("/manage/rooms/{id}/history")
    public ResponseEntity<?> roomHistory(@PathVariable Long id) {
        return withUser(u -> ResponseEntity.ok(boarding.roomHistory(u, id)));
    }

    // ── Students and rooms ───────────────────────────────────────────────────

    @GetMapping("/manage/find")
    public ResponseEntity<?> find(@RequestParam(required = false) String q, @RequestParam(required = false) Long roomId) {
        return withUser(u -> ResponseEntity.ok(boarding.find(u, q, roomId)));
    }

    @GetMapping("/manage/awaiting")
    public ResponseEntity<?> awaiting(@RequestParam(required = false) String gender, @RequestParam(required = false) Integer level,
                                      @RequestParam(required = false) Long programId, @RequestParam(required = false) String q) {
        return withUser(u -> ResponseEntity.ok(boarding.awaiting(u, gender, level, programId, q)));
    }

    @GetMapping("/manage/students/{id}")
    public ResponseEntity<?> student(@PathVariable Long id) {
        return withUser(u -> ResponseEntity.ok(boarding.studentHistory(u, id)));
    }

    @PutMapping("/manage/students/{id}")
    public ResponseEntity<?> updateStudent(@PathVariable Long id, @RequestBody BoardingService.StudentRequest req) {
        return withUser(u -> ResponseEntity.ok(boarding.updateStudent(u, id, req)));
    }

    @PostMapping("/manage/allocations")
    public ResponseEntity<?> assign(@RequestBody BoardingService.AssignRequest req) {
        return withUser(u -> ResponseEntity.ok(boarding.assign(u, req)));
    }

    @PostMapping("/manage/allocations/{id}/move")
    public ResponseEntity<?> move(@PathVariable Long id, @RequestBody BoardingService.MoveRequest req) {
        return withUser(u -> ResponseEntity.ok(boarding.move(u, id, req)));
    }

    @PostMapping("/manage/allocations/swap")
    public ResponseEntity<?> swap(@RequestBody BoardingService.SwapRequest req) {
        return withUser(u -> ResponseEntity.ok(boarding.swap(u, req)));
    }

    @PostMapping("/manage/allocations/{id}/vacate")
    public ResponseEntity<?> vacate(@PathVariable Long id, @RequestBody(required = false) BoardingService.VacateRequest req) {
        return withUser(u -> ResponseEntity.ok(boarding.vacate(u, id, req)));
    }

    // ── Many students at once (a check or preview first, then the real thing) ──

    public static class ImportRequest { public java.util.List<Map<String, String>> rows; }

    /** type = allocations (Student ID, House, Block, Room, Bed) | rooms (House, Block, Floor, Room, Beds, For, Label, Levels). */
    @PostMapping("/manage/import/{type}/check")
    public ResponseEntity<?> checkImport(@PathVariable String type, @RequestBody ImportRequest req) {
        return withUser(u -> ResponseEntity.ok(bulk.runImport(u, type, req.rows, false)));
    }

    @PostMapping("/manage/import/{type}")
    public ResponseEntity<?> runImport(@PathVariable String type, @RequestBody ImportRequest req) {
        return withUser(u -> ResponseEntity.ok(bulk.runImport(u, type, req.rows, true)));
    }

    @PostMapping("/manage/auto-allocate/preview")
    public ResponseEntity<?> autoPreview(@RequestBody BoardingBulkService.AutoRequest req) {
        return withUser(u -> ResponseEntity.ok(bulk.autoPreview(u, req)));
    }

    @PostMapping("/manage/auto-allocate")
    public ResponseEntity<?> autoAllocate(@RequestBody BoardingBulkService.AutoRequest req) {
        return withUser(u -> ResponseEntity.ok(bulk.autoCommit(u, req)));
    }

    /** New academic year: take students out of their rooms (all, or one house / level / programme). */
    @PostMapping("/manage/clear/preview")
    public ResponseEntity<?> clearPreview(@RequestBody(required = false) BoardingBulkService.ClearRequest req) {
        return withUser(u -> ResponseEntity.ok(bulk.clear(u, req, false)));
    }

    @PostMapping("/manage/clear")
    public ResponseEntity<?> clear(@RequestBody(required = false) BoardingBulkService.ClearRequest req) {
        return withUser(u -> ResponseEntity.ok(bulk.clear(u, req, true)));
    }

    // ── Staff and settings ───────────────────────────────────────────────────

    @GetMapping("/manage/staff")
    public ResponseEntity<?> staff() {
        return withUser(u -> ResponseEntity.ok(boarding.listStaff(u)));
    }

    @GetMapping("/manage/staff/candidates")
    public ResponseEntity<?> staffCandidates(@RequestParam(required = false) String q) {
        return withUser(u -> ResponseEntity.ok(boarding.staffCandidates(u, q)));
    }

    @PostMapping("/manage/staff")
    public ResponseEntity<?> addStaff(@RequestBody BoardingService.StaffRequest req) {
        return withUser(u -> ResponseEntity.ok(boarding.addStaff(u, req)));
    }

    @DeleteMapping("/manage/staff/{id}")
    public ResponseEntity<?> removeStaff(@PathVariable Long id) {
        return withUser(u -> ResponseEntity.ok(boarding.removeStaff(u, id)));
    }

    @GetMapping("/settings")
    public ResponseEntity<?> getSettings() {
        return withUser(u -> { settings.requireOn(); access.requireStaff(u); return ResponseEntity.ok(settings.view()); });
    }

    @PutMapping("/settings")
    public ResponseEntity<?> saveSettings(@RequestBody BoardingSettings.SettingsRequest req) {
        return withUser(u -> { settings.requireOn(); return ResponseEntity.ok(settings.save(u, req, access)); });
    }

    // ── Helpers ──────────────────────────────────────────────────────────────

    private interface UserAction { ResponseEntity<?> apply(User u); }

    private ResponseEntity<?> withUser(UserAction action) {
        Optional<User> user = currentUserService.current();
        if (user.isEmpty()) return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(new ErrorMessage("Please sign in."));
        try {
            return action.apply(user.get());
        } catch (BoardingSettings.BoardingOffException e) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN).body(Map.of("code", "BOARDING_OFF", "message", e.getMessage()));
        } catch (AccessDeniedException e) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN).body(new ErrorMessage(e.getMessage()));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(new ErrorMessage(e.getMessage()));
        } catch (DataIntegrityViolationException e) {
            // Two staff giving the same bed (or student) at the same moment
            return ResponseEntity.status(HttpStatus.CONFLICT).body(new ErrorMessage("Someone changed this room a moment ago. Refresh the page and try again."));
        } catch (RuntimeException e) {
            log.error("[Boarding] Request failed", e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(new ErrorMessage("Something went wrong. Please try again."));
        }
    }

    private static final org.slf4j.Logger log = org.slf4j.LoggerFactory.getLogger(BoardingController.class);
}
