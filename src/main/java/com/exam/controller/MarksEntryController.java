package com.exam.controller;

import com.exam.DTO.SemesterSheetDTO;
import com.exam.model.User;
import com.exam.model.exam.MarkSheetSection;
import com.exam.model.exam.SemesterSheet;
import com.exam.service.MarksEntryService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.web.bind.annotation.*;

import java.math.BigDecimal;
import java.security.Principal;
import java.util.ArrayList;
import java.util.List;

@RestController
@RequestMapping("/api/marks/sheet")
@CrossOrigin("*")
public class MarksEntryController {

    @Autowired
    private MarksEntryService marksEntryService;

    @Autowired
    private UserDetailsService userDetailsService;

    @Autowired
    private com.exam.service.PdfReportService pdfReportService;

    public static class ActivateSheetRequest {
        public Long programId;
        public String level;
        public Integer semester;
        public Long classTeacherId;
        public boolean restrictLecturerToAssignedCourses;
        public Long courseId;      // ID of the course selected for this sheet
        public List<SectionRequest> sections;

        public static class SectionRequest {
            public Long id;
            public String sectionName;
            public BigDecimal maxScore;
            public Boolean deletable;
        }
    }

    @PostMapping("/activate")
    public ResponseEntity<?> activateSheet(@RequestBody ActivateSheetRequest request) {
        try {
            List<MarkSheetSection> sections = new ArrayList<>();
            if (request.sections != null) {
                for (ActivateSheetRequest.SectionRequest secReq : request.sections) {
                    MarkSheetSection sec = new MarkSheetSection();
                    sec.setSectionName(secReq.sectionName);
                    sec.setMaxScore(secReq.maxScore);
                    if (secReq.deletable != null) {
                        sec.setDeletable(secReq.deletable);
                    }
                    sections.add(sec);
                }
            }

            SemesterSheet sheet = marksEntryService.activateSheet(
                    request.programId,
                    request.level,
                    request.semester,
                    request.classTeacherId,
                    request.restrictLecturerToAssignedCourses,
                    sections,
                    request.courseId
            );

            return ResponseEntity.ok(sheet.getId());
        } catch (RuntimeException e) {
            return ResponseEntity.badRequest().body(java.util.Map.of("message", e.getMessage()));
        }
    }

    @PutMapping("/{sheetId}")
    public ResponseEntity<?> updateSheet(@PathVariable Long sheetId, @RequestBody ActivateSheetRequest request) {
        try {
            List<com.exam.model.exam.MarkSheetSection> sections = new ArrayList<>();
            if (request.sections != null) {
                for (ActivateSheetRequest.SectionRequest secReq : request.sections) {
                    com.exam.model.exam.MarkSheetSection sec = new com.exam.model.exam.MarkSheetSection();
                    sec.setId(secReq.id);
                    sec.setSectionName(secReq.sectionName);
                    sec.setMaxScore(secReq.maxScore);
                    if (secReq.deletable != null) {
                        sec.setDeletable(secReq.deletable);
                    }
                    sections.add(sec);
                }
            }

            marksEntryService.updateSheet(
                    sheetId,
                    request.programId,
                    request.level,
                    request.semester,
                    request.classTeacherId,
                    request.restrictLecturerToAssignedCourses,
                    sections,
                    request.courseId
            );

            return ResponseEntity.ok(java.util.Map.of("message", "Sheet updated successfully"));
        } catch (RuntimeException e) {
            return ResponseEntity.badRequest().body(java.util.Map.of("message", e.getMessage()));
        }
    }

    @DeleteMapping("/{sheetId}")
    public ResponseEntity<?> deleteSheet(@PathVariable Long sheetId) {
        try {
            marksEntryService.deleteSheet(sheetId);
            return ResponseEntity.ok(java.util.Map.of("message", "Sheet deleted successfully"));
        } catch (RuntimeException e) {
            return ResponseEntity.badRequest().body(java.util.Map.of("message", e.getMessage()));
        }
    }

    @GetMapping("/all")
    public ResponseEntity<?> getAllSheets() {
        List<SemesterSheet> sheets = marksEntryService.getAllSheets();
        List<SemesterSheetDTO> response = new ArrayList<>();
        for (SemesterSheet sheet : sheets) {
            SemesterSheetDTO dto = new SemesterSheetDTO();
            dto.setId(sheet.getId());
            dto.setProgramId(sheet.getProgram() != null ? sheet.getProgram().getId() : null);
            dto.setProgramName(sheet.getProgram() != null ? sheet.getProgram().getName() : "N/A");
            dto.setLevel(sheet.getLevel());
            dto.setSemester(sheet.getSemester());
            dto.setStatus(sheet.getStatus());
            dto.setClassTeacherId(sheet.getClassTeacher() != null ? sheet.getClassTeacher().getId() : null);
            dto.setClassTeacherName(sheet.getClassTeacher() != null
                ? sheet.getClassTeacher().getFirstname() + " " + sheet.getClassTeacher().getLastname()
                : "Not Assigned");
            dto.setEnrolledStudentCount(marksEntryService.getEnrolledStudentCount(sheet.getId()));
            if (sheet.getCourses() != null && !sheet.getCourses().isEmpty()) {
                dto.setCourseId(sheet.getCourses().get(0).getCid());
                dto.setCourseName(sheet.getCourses().get(0).getTitle());
            }
            response.add(dto);
        }
        return ResponseEntity.ok(response);
    }

    /** Lecturer: returns only the sheets that contain courses assigned to this lecturer */
    @GetMapping("/my-sheets")
    public ResponseEntity<?> getMySheetsForLecturer(java.security.Principal principal) {
        if (principal == null) return ResponseEntity.status(401).build();
        List<SemesterSheet> sheets = marksEntryService.getSheetsForLecturer(principal.getName());
        List<SemesterSheetDTO> response = new ArrayList<>();
        for (SemesterSheet sheet : sheets) {
            SemesterSheetDTO dto = new SemesterSheetDTO();
            dto.setId(sheet.getId());
            dto.setProgramId(sheet.getProgram() != null ? sheet.getProgram().getId() : null);
            dto.setProgramName(sheet.getProgram() != null ? sheet.getProgram().getName() : "N/A");
            dto.setLevel(sheet.getLevel());
            dto.setSemester(sheet.getSemester());
            dto.setStatus(sheet.getStatus());
            dto.setClassTeacherId(sheet.getClassTeacher() != null ? sheet.getClassTeacher().getId() : null);
            dto.setClassTeacherName(sheet.getClassTeacher() != null
                ? sheet.getClassTeacher().getFirstname() + " " + sheet.getClassTeacher().getLastname()
                : "Not Assigned");
            dto.setEnrolledStudentCount(marksEntryService.getEnrolledStudentCount(sheet.getId()));
            if (sheet.getCourses() != null && !sheet.getCourses().isEmpty()) {
                dto.setCourseId(sheet.getCourses().get(0).getCid());
                dto.setCourseName(sheet.getCourses().get(0).getTitle());
            }
            response.add(dto);
        }
        return ResponseEntity.ok(response);
    }

    @GetMapping("/{sheetId}")
    public ResponseEntity<?> getSheetData(@PathVariable Long sheetId) {
        SemesterSheetDTO dto = marksEntryService.getSheetData(sheetId);
        if (dto == null) {
            return ResponseEntity.notFound().build();
        }
        return ResponseEntity.ok(dto);
    }

    @PostMapping("/{sheetId}/save")
    public ResponseEntity<?> saveMarks(@PathVariable Long sheetId, @RequestBody SemesterSheetDTO dto) {
        marksEntryService.saveMarks(sheetId, dto);
        return ResponseEntity.ok().build();
    }

    @PostMapping("/{sheetId}/submit")
    public ResponseEntity<?> submitSheet(@PathVariable Long sheetId) {
        marksEntryService.submitSheet(sheetId);
        return ResponseEntity.ok().build();
    }

    @PostMapping("/{sheetId}/publish")
    public ResponseEntity<?> publishSheet(@PathVariable Long sheetId) {
        marksEntryService.publishSheet(sheetId);
        return ResponseEntity.ok().build();
    }

    /** Admin/SuperAdmin: revert a SUBMITTED sheet back to ACTIVE so the lecturer can correct marks */
    @PostMapping("/{sheetId}/revert")
    public ResponseEntity<?> revertSheet(@PathVariable Long sheetId) {
        marksEntryService.revertSheet(sheetId);
        return ResponseEntity.ok(java.util.Map.of("message", "Sheet reverted to lecturer for corrections."));
    }

    /** Admin/SuperAdmin: approve a SUBMITTED sheet — unlocks the Publish button */
    @PostMapping("/{sheetId}/approve")
    public ResponseEntity<?> approveSheet(@PathVariable Long sheetId) {
        marksEntryService.approveSheet(sheetId);
        return ResponseEntity.ok(java.util.Map.of("message", "Sheet approved successfully."));
    }

    /** Admin/SuperAdmin: re-enroll all matching students into an existing sheet */
    @PostMapping("/{sheetId}/enroll-students")
    public ResponseEntity<?> enrollStudents(@PathVariable Long sheetId) {
        int count = marksEntryService.enrollStudentsIntoSheet(sheetId);
        return ResponseEntity.ok(java.util.Map.of("enrolled", count));
    }

    @PostMapping("/{sheetId}/sync-marks/{studentId}")
    public ResponseEntity<?> syncMarksForStudent(@PathVariable Long sheetId, @PathVariable Long studentId, @RequestParam Long sectionId) {
        marksEntryService.syncSystemMarksForStudent(sheetId, studentId, sectionId);
        return ResponseEntity.ok(java.util.Map.of("message", "Marks synced successfully for student"));
    }

    @PostMapping("/{sheetId}/sync-marks/bulk")
    public ResponseEntity<?> syncMarksBulk(@PathVariable Long sheetId, @RequestParam Long sectionId) {
        marksEntryService.syncSystemMarksBulk(sheetId, sectionId);
        return ResponseEntity.ok(java.util.Map.of("message", "Marks synced successfully for all students"));
    }

    @PostMapping("/{sheetId}/sections")
    public ResponseEntity<?> addSection(@PathVariable Long sheetId, @RequestBody java.util.Map<String, Object> payload) {
        try {
            String sectionName = (String) payload.get("sectionName");
            BigDecimal maxScore = new BigDecimal(payload.get("maxScore").toString());
            MarkSheetSection newSection = marksEntryService.addSection(sheetId, sectionName, maxScore);
            return ResponseEntity.ok(newSection);
        } catch (Exception e) {
            return ResponseEntity.badRequest().body(java.util.Map.of("message", e.getMessage()));
        }
    }

    @DeleteMapping("/{sheetId}/sections/{sectionId}")
    public ResponseEntity<?> deleteSection(@PathVariable Long sheetId, @PathVariable Long sectionId) {
        try {
            marksEntryService.deleteSection(sheetId, sectionId);
            return ResponseEntity.ok(java.util.Map.of("message", "Section deleted"));
        } catch (Exception e) {
            return ResponseEntity.badRequest().body(java.util.Map.of("message", e.getMessage()));
        }
    }

    /**
     * Returns the marks for the currently logged-in student.
     * Matches by USERNAME (from JWT principal) against the full sheet data.
     */
    @GetMapping("/{sheetId}/my-marks")
    public ResponseEntity<?> getMyMarks(@PathVariable Long sheetId, Principal principal) {
        if (principal == null) return ResponseEntity.status(401).build();

        System.out.println("[getMyMarks] sheetId=" + sheetId + " | principal=" + principal.getName());

        SemesterSheetDTO sheetDto = marksEntryService.getSheetData(sheetId);
        if (sheetDto == null) {
            System.out.println("[getMyMarks] Sheet not found: " + sheetId);
            return ResponseEntity.status(404).body("Sheet not found.");
        }

        String username = principal.getName();
        System.out.println("[getMyMarks] Total students in sheet: "
                + (sheetDto.getStudentMarks() != null ? sheetDto.getStudentMarks().size() : 0));

        SemesterSheetDTO.StudentMarkDTO myMarks = null;
        if (sheetDto.getStudentMarks() != null) {
            // Log all usernames in the sheet for comparison
            sheetDto.getStudentMarks().forEach(sm ->
                System.out.println("[getMyMarks]   Sheet student username='" + sm.getUsername()
                        + "' | courses=" + (sm.getCourseMarks() != null ? sm.getCourseMarks().size() : 0)));

            myMarks = sheetDto.getStudentMarks().stream()
                    .filter(sm -> username.equals(sm.getUsername()))
                    .findFirst()
                    .orElse(null);
        }

        if (myMarks != null) {
            System.out.println("[getMyMarks] MATCHED by username='" + username
                    + "' | courseMarks=" + myMarks.getCourseMarks().size());
            myMarks.getCourseMarks().forEach(cm ->
                System.out.println("[getMyMarks]   Course=" + cm.getCourseCode()
                        + " | total=" + cm.getTotalScore()
                        + " | grade=" + cm.getGrade()));
        } else {
            System.out.println("[getMyMarks] NO MATCH for username='" + username + "' — falling back to getStudentMarks");
            User user = (User) userDetailsService.loadUserByUsername(username);
            myMarks = marksEntryService.getStudentMarks(sheetId, user.getId());
        }

        if (myMarks == null) {
            User user = (User) userDetailsService.loadUserByUsername(username);
            System.out.println("[getMyMarks] Auto-building empty DTO for userId=" + user.getId());
            myMarks = new SemesterSheetDTO.StudentMarkDTO();
            myMarks.setStudentId(user.getId());
            myMarks.setStudentName(user.getFirstname() + " " + user.getLastname());
            myMarks.setUsername(user.getUsername());
            myMarks.setCourseMarks(new java.util.ArrayList<>());
        }

        return ResponseEntity.ok(myMarks);
    }

    /**
     * GET /api/marks/sheet/{sheetId}/report-card/pdf
     * Downloads the student's semester report card as a PDF.
     * The student is identified by the JWT principal.
     */
    @GetMapping("/{sheetId}/report-card/pdf")
    public org.springframework.http.ResponseEntity<?> downloadSemesterReportCard(
            @PathVariable Long sheetId, Principal principal) {

        if (principal == null) return org.springframework.http.ResponseEntity.status(401).body("Not authenticated.");

        try {
            com.exam.model.User user = (com.exam.model.User) userDetailsService.loadUserByUsername(principal.getName());
            java.util.Map<String, Object> data = marksEntryService.getStudentReportCardData(sheetId, user.getId());

            if (data == null) {
                return org.springframework.http.ResponseEntity.status(404).body("No marks found for this student in this sheet.");
            }

            byte[] pdf = pdfReportService.generateSemesterReportCardPdf(data, user.getUsername().toUpperCase());

            String filename = "SemesterReportCard_" + data.get("programName") + "_Level" + data.get("level") + "_Sem" + data.get("semester") + ".pdf";
            filename = filename.replaceAll("[^a-zA-Z0-9_.\\-]", "_");

            org.springframework.http.HttpHeaders headers = new org.springframework.http.HttpHeaders();
            headers.setContentType(org.springframework.http.MediaType.APPLICATION_PDF);
            headers.setContentDisposition(
                org.springframework.http.ContentDisposition.attachment().filename(filename).build());
            headers.setContentLength(pdf.length);

            return new org.springframework.http.ResponseEntity<>(pdf, headers, org.springframework.http.HttpStatus.OK);
        } catch (Exception e) {
            e.printStackTrace();
            return org.springframework.http.ResponseEntity.status(500).body("Failed to generate report card: " + e.getMessage());
        }
    }
}
