package com.exam.service;

import com.exam.model.User;
import com.exam.model.exam.*;
import com.exam.repository.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

@Service
public class MarksEntryService {

    @Autowired
    private SemesterSheetRepository semesterSheetRepository;

    @Autowired
    private CategoryRepository categoryRepository; // Courses

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private ProgramRepository programRepository;

    @Autowired
    private StudentCourseMarkRepository studentCourseMarkRepository;

    @Autowired
    private Registered_coursesRepository registeredCoursesRepository;

    @Autowired
    private StudentSectionMarkRepository studentSectionMarkRepository;

    @Autowired
    private ReportRepository reportRepository;

    @Autowired
    private MarkSheetSectionRepository markSheetSectionRepository;

    @Transactional
    public SemesterSheet activateSheet(Long programId, String level, Integer semester,
                                       Long classTeacherId, boolean restrictLecturer,
                                       List<MarkSheetSection> sections, Long courseId) {

        if (sections == null || sections.isEmpty()) {
            throw new RuntimeException("At least one section must be provided.");
        }
        
        BigDecimal totalScore = sections.stream()
            .map(MarkSheetSection::getMaxScore)
            .reduce(BigDecimal.ZERO, BigDecimal::add);
            
        if (totalScore.compareTo(new BigDecimal("100")) != 0) {
            throw new RuntimeException("The total marks for all sections must sum to exactly 100. Current sum: " + totalScore);
        }

        // The sheet belongs to the program specified in the request
        Program program = programRepository.findById(programId)
                .orElseThrow(() -> new RuntimeException("Program not found"));

        // Duplicate check: a sheet already exists for this exact program+level+semester
        List<SemesterSheet> existing = semesterSheetRepository.findByProgramIdAndLevelAndSemester(
                program.getId(), level, semester);
        if (existing != null && !existing.isEmpty()) {
            // Check if this course already has a sheet for this program+level+semester
            for (SemesterSheet ex : existing) {
                List<StudentCourseMark> overlapping = studentCourseMarkRepository
                    .findBySemesterSheetId(ex.getId());
                boolean conflict = overlapping.stream()
                    .anyMatch(scm -> courseId != null && courseId.equals(scm.getCourse().getCid()));
                if (conflict) {
                    throw new RuntimeException(
                        "This course already has an active sheet for this Level and Semester.");
                }
            }
        }

        // Fetch the single course
        Category course = null;
        if (courseId != null) {
            course = categoryRepository.findById(courseId)
                .orElseThrow(() -> new RuntimeException("Course not found"));
        }
        
        List<Category> selectedCourses = new ArrayList<>();
        if (course != null) selectedCourses.add(course);
        
        User classTeacher = null;
        if (classTeacherId != null) {
            classTeacher = userRepository.findById(classTeacherId).orElse(null);
        }

        SemesterSheet sheet = new SemesterSheet();
        sheet.setProgram(program);
        sheet.setLevel(level);
        sheet.setSemester(semester);
        sheet.setClassTeacher(classTeacher);
        sheet.setRestrictLecturerToAssignedCourses(restrictLecturer);
        sheet.setStatus("DRAFT");

        for (MarkSheetSection section : sections) {
            section.setSemesterSheet(sheet);
        }
        sheet.setSections(sections);
        sheet.setCourses(new ArrayList<>(selectedCourses));

        SemesterSheet savedSheet = semesterSheetRepository.save(sheet);

        // Fetch students for this program+level
        int levelInt = 100;
        try { levelInt = Integer.parseInt(level); } catch (NumberFormatException ignored) {}
        List<User> students = userRepository.findByProgramAndCurrentLevel(program, levelInt);

        // Generate blank marks for each student × each selected course
        for (User student : students) {
            for (Category c : selectedCourses) {
                StudentCourseMark scm = new StudentCourseMark();
                scm.setSemesterSheet(savedSheet);
                scm.setStudent(student);
                scm.setCourse(c);
                scm.setTotalScore(BigDecimal.ZERO);
                scm.setGrade("N/A");

                for (MarkSheetSection sec : sections) {
                    StudentSectionMark ssm = new StudentSectionMark();
                    ssm.setStudentCourseMark(scm);
                    ssm.setSection(sec);
                    ssm.setScoreObtained(BigDecimal.ZERO);
                    scm.getSectionMarks().add(ssm);
                }
                studentCourseMarkRepository.save(scm);
            }
        }
        return savedSheet;
    }


    public void deleteSheet(Long sheetId) {
        SemesterSheet sheet = semesterSheetRepository.findById(sheetId)
            .orElseThrow(() -> new RuntimeException("Sheet not found"));
            
        if (!"DRAFT".equals(sheet.getStatus()) && !"ACTIVE".equals(sheet.getStatus())) {
            throw new RuntimeException("Only DRAFT and ACTIVE sheets can be deleted.");
        }
        
        semesterSheetRepository.delete(sheet);
    }

    @Transactional
    public SemesterSheet updateSheet(Long sheetId, Long programId, String level, Integer semester,
                                     Long classTeacherId, boolean restrictLecturer,
                                     List<MarkSheetSection> newSections, Long courseId) {
        SemesterSheet sheet = semesterSheetRepository.findById(sheetId)
            .orElseThrow(() -> new RuntimeException("Sheet not found"));
            
        if (!"DRAFT".equals(sheet.getStatus()) && !"ACTIVE".equals(sheet.getStatus())) {
            throw new RuntimeException("Only DRAFT and ACTIVE sheets can be edited.");
        }

        boolean cohortChanged = false;
        boolean courseChanged = false;
        
        Program newProgram = programRepository.findById(programId)
                .orElseThrow(() -> new RuntimeException("Program not found"));

        if (!sheet.getProgram().getId().equals(programId) || 
            !sheet.getLevel().equals(level) || 
            !sheet.getSemester().equals(semester)) {
            cohortChanged = true;
        }

        Long oldCourseId = sheet.getCourses().isEmpty() ? null : sheet.getCourses().get(0).getCid();
        if (courseId != null && !courseId.equals(oldCourseId)) {
            courseChanged = true;
        }

        // Apply basic updates
        sheet.setProgram(newProgram);
        sheet.setLevel(level);
        sheet.setSemester(semester);
        sheet.setRestrictLecturerToAssignedCourses(restrictLecturer);

        User classTeacher = null;
        if (classTeacherId != null) {
            classTeacher = userRepository.findById(classTeacherId).orElse(null);
        }
        sheet.setClassTeacher(classTeacher);

        if (courseChanged) {
            Category newCourse = categoryRepository.findById(courseId)
                .orElseThrow(() -> new RuntimeException("Course not found"));
            List<Category> courses = new ArrayList<>();
            courses.add(newCourse);
            sheet.setCourses(courses);
        }

        // Sync sections
        // Remove sections not in new list
        List<Long> newSectionIds = newSections.stream().map(MarkSheetSection::getId).filter(id -> id != null).collect(java.util.stream.Collectors.toList());
        sheet.getSections().removeIf(s -> !newSectionIds.contains(s.getId()) && s.getId() != null);
        
        for (MarkSheetSection newSec : newSections) {
            if (newSec.getId() == null) {
                newSec.setSemesterSheet(sheet);
                sheet.getSections().add(newSec);
            } else {
                MarkSheetSection existingSec = sheet.getSections().stream()
                    .filter(s -> s.getId().equals(newSec.getId())).findFirst().orElse(null);
                if (existingSec != null) {
                    existingSec.setSectionName(newSec.getSectionName());
                    existingSec.setMaxScore(newSec.getMaxScore());
                    existingSec.setDeletable(newSec.isDeletable());
                }
            }
        }

        // Validate max score
        BigDecimal totalScore = sheet.getSections().stream()
            .map(MarkSheetSection::getMaxScore)
            .reduce(BigDecimal.ZERO, BigDecimal::add);
        if (totalScore.compareTo(new BigDecimal("100")) != 0) {
            throw new RuntimeException("The total marks for all sections must sum to exactly 100.");
        }

        SemesterSheet savedSheet = semesterSheetRepository.save(sheet);

        if (cohortChanged || courseChanged) {
            // Drop old marks
            studentCourseMarkRepository.deleteAll(sheet.getCourseMarks());
            sheet.getCourseMarks().clear();
            
            // Generate new marks
            int levelInt = 100;
            try { levelInt = Integer.parseInt(level); } catch (NumberFormatException ignored) {}
            List<User> students = userRepository.findByProgramAndCurrentLevel(newProgram, levelInt);
            
            Category currentCourse = sheet.getCourses().isEmpty() ? null : sheet.getCourses().get(0);

            if (currentCourse != null) {
                for (User student : students) {
                    StudentCourseMark scm = new StudentCourseMark();
                    scm.setSemesterSheet(savedSheet);
                    scm.setStudent(student);
                    scm.setCourse(currentCourse);
                    scm.setTotalScore(BigDecimal.ZERO);
                    scm.setGrade("N/A");

                    for (MarkSheetSection sec : savedSheet.getSections()) {
                        StudentSectionMark ssm = new StudentSectionMark();
                        ssm.setStudentCourseMark(scm);
                        ssm.setSection(sec);
                        ssm.setScoreObtained(BigDecimal.ZERO);
                        scm.getSectionMarks().add(ssm);
                    }
                    studentCourseMarkRepository.save(scm);
                }
            }
        } else {
            // Re-enroll any new students just in case, this is non-destructive
            enrollStudentsIntoSheet(sheetId);
        }

        return savedSheet;
    }

    public List<SemesterSheet> getAllSheets() {
        return semesterSheetRepository.findAll();
    }

    /**
     * Returns only the sheets that contain at least one course assigned to
     * the given lecturer (Category.user == lecturer).
     * If the lecturer has no assigned courses, returns all sheets (fallback).
     */
    public List<SemesterSheet> getSheetsForLecturer(String username) {
        User lecturer = userRepository.findByUsername(username).orElse(null);
        if (lecturer == null) return new java.util.ArrayList<>();

        List<SemesterSheet> allSheets = semesterSheetRepository.findAll();

        // Get all course IDs assigned to this lecturer
        java.util.Set<Long> assignedCourseIds = categoryRepository.findAll().stream()
            .filter(c -> c.getUser() != null && c.getUser().getId().equals(lecturer.getId()))
            .map(c -> c.getCid())
            .collect(java.util.stream.Collectors.toSet());

        if (assignedCourseIds.isEmpty()) {
            // No assigned courses → lecturer sees no sheets (don't expose all)
            return new java.util.ArrayList<>();
        }

        // Keep only sheets that have at least one of this lecturer's courses
        return allSheets.stream()
            .filter(sheet -> sheet.getCourses().stream()
                .anyMatch(c -> assignedCourseIds.contains(c.getCid())))
            .collect(java.util.stream.Collectors.toList());
    }

    public SemesterSheet getSheetById(Long sheetId) {
        return semesterSheetRepository.findById(sheetId).orElse(null);
    }

    @Transactional
    public void submitSheet(Long sheetId) {
        SemesterSheet sheet = getSheetById(sheetId);
        if (sheet != null) {
            sheet.setStatus("SUBMITTED");
            semesterSheetRepository.save(sheet);
        }
    }

    @Transactional
    public void approveSheet(Long sheetId) {
        SemesterSheet sheet = getSheetById(sheetId);
        if (sheet != null) {
            sheet.setStatus("APPROVED");
            semesterSheetRepository.save(sheet);
        }
    }

    @Transactional
    public void publishSheet(Long sheetId) {
        SemesterSheet sheet = getSheetById(sheetId);
        if (sheet != null) {
            if (!"APPROVED".equals(sheet.getStatus())) {
                throw new RuntimeException("Sheet must be approved before publishing.");
            }
            sheet.setStatus("PUBLISHED");
            semesterSheetRepository.save(sheet);
        }
    }

    /**
     * Admin/SuperAdmin: revert a SUBMITTED or APPROVED sheet back to ACTIVE
     * so the lecturer can correct marks.
     */
    @Transactional
    public void revertSheet(Long sheetId) {
        SemesterSheet sheet = getSheetById(sheetId);
        if (sheet != null) {
            sheet.setStatus("ACTIVE");
            semesterSheetRepository.save(sheet);
        }
    }

    /**
     * Gathers all data needed to render the student's semester report card PDF.
     * Returns null if the student has no marks in this sheet.
     */
    public java.util.Map<String, Object> getStudentReportCardData(Long sheetId, Long userId) {
        com.exam.DTO.SemesterSheetDTO sheetDto = getSheetData(sheetId);
        if (sheetDto == null) return null;

        com.exam.DTO.SemesterSheetDTO.StudentMarkDTO myMark = null;
        if (sheetDto.getStudentMarks() != null) {
            myMark = sheetDto.getStudentMarks().stream()
                    .filter(sm -> userId.equals(sm.getStudentId()))
                    .findFirst().orElse(null);
        }
        if (myMark == null) return null;

        java.util.Map<String, Object> data = new java.util.LinkedHashMap<>();
        data.put("studentName",  myMark.getStudentName());
        data.put("username",     myMark.getUsername());
        data.put("programName",  sheetDto.getProgramName());
        data.put("level",        sheetDto.getLevel());
        data.put("semester",     sheetDto.getSemester());
        data.put("sections",     sheetDto.getSections());
        data.put("courseMarks",  myMark.getCourseMarks());
        data.put("generatedDate", java.time.LocalDate.now().format(java.time.format.DateTimeFormatter.ofPattern("dd MMM yyyy")));
        return data;
    }


    /** Returns the number of distinct students enrolled in the given sheet. */
    public int getEnrolledStudentCount(Long sheetId) {
        List<StudentCourseMark> marks = studentCourseMarkRepository.findBySemesterSheetId(sheetId);
        return (int) marks.stream()
                .map(m -> m.getStudent().getId())
                .distinct()
                .count();
    }

    public String getCourseNamesForSheet(Long sheetId) {
        SemesterSheet sheet = getSheetById(sheetId);
        if (sheet == null || sheet.getCourses() == null || sheet.getCourses().isEmpty()) {
            // Fallback for older sheets created before this field existed
            List<String> courseNames = studentCourseMarkRepository.findBySemesterSheetId(sheetId).stream()
                    .map(scm -> scm.getCourse().getTitle())
                    .distinct()
                    .collect(java.util.stream.Collectors.toList());
            return String.join(", ", courseNames);
        }
        List<String> courseNames = sheet.getCourses().stream()
                .map(Category::getTitle)
                .collect(java.util.stream.Collectors.toList());
        return String.join(", ", courseNames);
    }

    public com.exam.DTO.SemesterSheetDTO getSheetData(Long sheetId) {
        SemesterSheet sheet = getSheetById(sheetId);
        if (sheet == null) return null;

        com.exam.DTO.SemesterSheetDTO dto = new com.exam.DTO.SemesterSheetDTO();
        dto.setId(sheet.getId());
        dto.setProgramId(sheet.getProgram() != null ? sheet.getProgram().getId() : null);
        dto.setLevel(sheet.getLevel());
        dto.setSemester(sheet.getSemester());
        dto.setStatus(sheet.getStatus());
        dto.setClassTeacherId(sheet.getClassTeacher() != null ? sheet.getClassTeacher().getId() : null);
        dto.setRestrictLecturerToAssignedCourses(sheet.isRestrictLecturerToAssignedCourses());

        if (sheet.getCourses() != null && !sheet.getCourses().isEmpty()) {
            dto.setCourseId(sheet.getCourses().get(0).getCid());
            dto.setCourseName(sheet.getCourses().get(0).getTitle());
        }

        List<com.exam.DTO.SemesterSheetDTO.SectionDTO> sectionDTOs = new ArrayList<>();
        for (MarkSheetSection sec : sheet.getSections()) {
            com.exam.DTO.SemesterSheetDTO.SectionDTO secDto = new com.exam.DTO.SemesterSheetDTO.SectionDTO();
            secDto.setId(sec.getId());
            secDto.setSectionName(sec.getSectionName());
            secDto.setMaxScore(sec.getMaxScore());
            secDto.setDeletable(sec.isDeletable());
            sectionDTOs.add(secDto);
        }
        dto.setSections(sectionDTOs);

        // Group course marks by student
        List<StudentCourseMark> courseMarks = studentCourseMarkRepository.findBySemesterSheetId(sheetId);
        System.out.println("[getSheetData] sheetId=" + sheetId + " | total SCM rows=" + courseMarks.size());
        java.util.Map<Long, com.exam.DTO.SemesterSheetDTO.StudentMarkDTO> studentMap = new java.util.HashMap<>();

        for (StudentCourseMark scm : courseMarks) {
            User student = scm.getStudent();
            System.out.println("[getSheetData]   SCM id=" + scm.getId()
                + " | student=" + student.getUsername()
                + " | course=" + scm.getCourse().getCourseCode()
                + " | totalScore=" + scm.getTotalScore()
                + " | sectionMarks=" + scm.getSectionMarks().size());
            for (StudentSectionMark ssm : scm.getSectionMarks()) {
                System.out.println("[getSheetData]     SSM id=" + ssm.getId()
                    + " | sectionId=" + ssm.getSection().getId()
                    + " | score=" + ssm.getScoreObtained());
            }

            com.exam.DTO.SemesterSheetDTO.StudentMarkDTO studentDto = studentMap.computeIfAbsent(student.getId(), k -> {
                com.exam.DTO.SemesterSheetDTO.StudentMarkDTO s = new com.exam.DTO.SemesterSheetDTO.StudentMarkDTO();
                s.setStudentId(student.getId());
                s.setStudentName(student.getFirstname() + " " + student.getLastname());
                s.setUsername(student.getUsername());
                s.setCourseMarks(new ArrayList<>());
                return s;
            });

            com.exam.DTO.SemesterSheetDTO.CourseMarkDTO cmDto = new com.exam.DTO.SemesterSheetDTO.CourseMarkDTO();
            cmDto.setCourseMarkId(scm.getId());
            cmDto.setCourseId(scm.getCourse().getCid());
            cmDto.setCourseTitle(scm.getCourse().getTitle());
            cmDto.setCourseCode(scm.getCourse().getCourseCode());
            cmDto.setTotalScore(scm.getTotalScore());
            cmDto.setGrade(scm.getGrade());

            List<com.exam.DTO.SemesterSheetDTO.SectionMarkDTO> sectionMarkDTOs = new ArrayList<>();
            for (StudentSectionMark ssm : scm.getSectionMarks()) {
                com.exam.DTO.SemesterSheetDTO.SectionMarkDTO ssmDto = new com.exam.DTO.SemesterSheetDTO.SectionMarkDTO();
                ssmDto.setSectionMarkId(ssm.getId());
                ssmDto.setSectionId(ssm.getSection().getId());
                ssmDto.setScoreObtained(ssm.getScoreObtained());
                sectionMarkDTOs.add(ssmDto);
            }
            cmDto.setSectionMarks(sectionMarkDTOs);
            studentDto.getCourseMarks().add(cmDto);
        }

        dto.setStudentMarks(new ArrayList<>(studentMap.values()));
        return dto;
    }

    @Transactional
    public void saveMarks(Long sheetId, com.exam.DTO.SemesterSheetDTO dto) {
        SemesterSheet sheet = getSheetById(sheetId);
        if (sheet == null) {
            System.out.println("[saveMarks] ERROR: Sheet not found: " + sheetId);
            return;
        }
        if (dto.getStudentMarks() == null) {
            System.out.println("[saveMarks] ERROR: studentMarks is null in DTO");
            return;
        }

        System.out.println("[saveMarks] Saving marks for sheetId=" + sheetId
                + ", students=" + dto.getStudentMarks().size());

        for (com.exam.DTO.SemesterSheetDTO.StudentMarkDTO studentMark : dto.getStudentMarks()) {
            if (studentMark.getCourseMarks() == null) continue;
            System.out.println("[saveMarks]  Student: " + studentMark.getUsername()
                    + " | courses=" + studentMark.getCourseMarks().size());

            for (com.exam.DTO.SemesterSheetDTO.CourseMarkDTO courseMark : studentMark.getCourseMarks()) {
                System.out.println("[saveMarks]    CourseMarkId=" + courseMark.getCourseMarkId()
                        + " | course=" + courseMark.getCourseCode());

                StudentCourseMark scm = studentCourseMarkRepository.findById(courseMark.getCourseMarkId()).orElse(null);
                if (scm == null) {
                    System.out.println("[saveMarks]    ERROR: StudentCourseMark NOT FOUND for id="
                            + courseMark.getCourseMarkId());
                    continue;
                }

                BigDecimal total = BigDecimal.ZERO;

                if (courseMark.getSectionMarks() != null) {
                    for (com.exam.DTO.SemesterSheetDTO.SectionMarkDTO sectionMark : courseMark.getSectionMarks()) {
                        System.out.println("[saveMarks]      SectionMarkId=" + sectionMark.getSectionMarkId()
                                + " | score=" + sectionMark.getScoreObtained());

                        StudentSectionMark ssm = studentSectionMarkRepository
                            .findById(sectionMark.getSectionMarkId()).orElse(null);
                        if (ssm != null) {
                            BigDecimal score = sectionMark.getScoreObtained() != null
                                    ? sectionMark.getScoreObtained() : BigDecimal.ZERO;
                            ssm.setScoreObtained(score);
                            studentSectionMarkRepository.save(ssm);
                            total = total.add(score);
                            System.out.println("[saveMarks]      SAVED ssm id=" + ssm.getId() + " score=" + score);
                        } else {
                            System.out.println("[saveMarks]      ERROR: StudentSectionMark NOT FOUND for id="
                                    + sectionMark.getSectionMarkId());
                        }
                    }
                }

                scm.setTotalScore(total);
                scm.setGrade(calculateGrade(total));
                studentCourseMarkRepository.save(scm);
                System.out.println("[saveMarks]    Saved SCM id=" + scm.getId()
                        + " total=" + total + " grade=" + scm.getGrade());
            }
        }
        System.out.println("[saveMarks] DONE for sheetId=" + sheetId);
    }

    private String calculateGrade(BigDecimal total) {
        if (total == null) return "F";
        double percentage = total.doubleValue();
        if (percentage >= 90) return "A+";
        if (percentage >= 80) return "A";
        if (percentage >= 70) return "B";
        if (percentage >= 60) return "C";
        if (percentage >= 50) return "D";
        return "F";
    }

    /**
     * Admin: Re-enroll all matching students into an existing sheet.
     * Also handles mismatched level formats ("100" vs "Level 100").
     * Returns number of new StudentCourseMark rows created.
     */
    @Transactional
    public int enrollStudentsIntoSheet(Long sheetId) {
        SemesterSheet sheet = getSheetById(sheetId);
        if (sheet == null) return 0;

        Program program = sheet.getProgram();
        String level = sheet.getLevel();
        Integer semester = sheet.getSemester();

        // Resolve courses (try multiple level formats)
        List<Category> courses = categoryRepository.findByProgramsContainingAndLevelAndSemester(program, level, semester);
        if (courses.isEmpty()) {
            courses = categoryRepository.findByProgramsContainingAndLevelAndSemester(program, "Level " + level, semester);
        }
        if (courses.isEmpty()) {
            courses = categoryRepository.findByProgramsContaining(program).stream()
                .filter(c -> semester != null && semester.equals(c.getSemester()))
                .collect(java.util.stream.Collectors.toList());
        }

        // Resolve students (try matching by program+level, fallback to all students of the program)
        int levelInt = 0;
        try { levelInt = Integer.parseInt(level != null ? level.replace("Level ", "").trim() : "0"); } catch (Exception ignored) {}
        List<User> students = userRepository.findByProgramAndCurrentLevel(program, levelInt);
        if (students.isEmpty()) {
            // Fallback: all NORMAL users for this program regardless of level
            students = userRepository.findAll().stream()
                .filter(u -> u.getProgram() != null && u.getProgram().getId().equals(program.getId())
                          && com.exam.model.Role.NORMAL.equals(u.getRole()))
                .collect(java.util.stream.Collectors.toList());
        }

        int count = 0;
        for (User student : students) {
            for (Category course : courses) {
                // Skip if row already exists
                boolean exists = !studentCourseMarkRepository
                    .findBySemesterSheetIdAndStudentId(sheetId, student.getId()).stream()
                    .filter(scm -> scm.getCourse().getCid().equals(course.getCid()))
                    .collect(java.util.stream.Collectors.toList()).isEmpty();
                if (exists) continue;

                StudentCourseMark scm = new StudentCourseMark();
                scm.setSemesterSheet(sheet);
                scm.setStudent(student);
                scm.setCourse(course);
                scm.setTotalScore(BigDecimal.ZERO);
                scm.setGrade("N/A");
                for (MarkSheetSection sec : sheet.getSections()) {
                    StudentSectionMark ssm = new StudentSectionMark();
                    ssm.setStudentCourseMark(scm);
                    ssm.setSection(sec);
                    ssm.setScoreObtained(BigDecimal.ZERO);
                    scm.getSectionMarks().add(ssm);
                }
                studentCourseMarkRepository.save(scm);
                count++;
            }
        }
        return count;
    }

    /**
     * Sync system assessment marks for a specific student into the active sheet.
     */
    @Transactional
    public void syncSystemMarksForStudent(Long sheetId, Long studentId, Long targetSectionId) {
        SemesterSheet sheet = getSheetById(sheetId);
        if (sheet == null || "SUBMITTED".equals(sheet.getStatus()) || "PUBLISHED".equals(sheet.getStatus())) {
            return;
        }

        List<Report> reports = reportRepository.findByUser_Id(studentId);
        if (reports == null || reports.isEmpty()) return;

        List<StudentCourseMark> scms = studentCourseMarkRepository.findBySemesterSheetId(sheetId)
                .stream().filter(s -> s.getStudent().getId().equals(studentId))
                .collect(java.util.stream.Collectors.toList());

        for (StudentCourseMark scm : scms) {
            Long courseId = scm.getCourse().getCid();
            
            // Sum all marks + marksB for this course
            BigDecimal courseTotalFromSystem = BigDecimal.ZERO;
            for (Report r : reports) {
                if (r.getQuiz() != null && r.getQuiz().getCategory() != null 
                    && r.getQuiz().getCategory().getCid().equals(courseId)) {
                    if (r.getMarks() != null) courseTotalFromSystem = courseTotalFromSystem.add(r.getMarks());
                    if (r.getMarksB() != null) courseTotalFromSystem = courseTotalFromSystem.add(r.getMarksB());
                }
            }

            if (courseTotalFromSystem.compareTo(BigDecimal.ZERO) >= 0 && scm.getSectionMarks() != null) {
                // Find the target section
                StudentSectionMark targetSection = scm.getSectionMarks().stream()
                        .filter(ssm -> ssm.getSection().getId().equals(targetSectionId))
                        .findFirst()
                        .orElse(null);
                
                if (targetSection != null) {
                    // ensure it doesn't exceed maxScore
                    if (courseTotalFromSystem.compareTo(targetSection.getSection().getMaxScore()) > 0) {
                        targetSection.setScoreObtained(targetSection.getSection().getMaxScore());
                    } else {
                        targetSection.setScoreObtained(courseTotalFromSystem);
                    }
                    studentSectionMarkRepository.save(targetSection);
                }

                // recalculate scm total and grade
                BigDecimal total = BigDecimal.ZERO;
                for (StudentSectionMark ssm : scm.getSectionMarks()) {
                    total = total.add(ssm.getScoreObtained() != null ? ssm.getScoreObtained() : BigDecimal.ZERO);
                }
                scm.setTotalScore(total);
                scm.setGrade(calculateGrade(total));
                studentCourseMarkRepository.save(scm);
            }
        }
    }

    /**
     * Bulk sync system assessment marks for all students in the sheet.
     */
    @Transactional
    public void syncSystemMarksBulk(Long sheetId, Long targetSectionId) {
        SemesterSheet sheet = getSheetById(sheetId);
        if (sheet == null || "SUBMITTED".equals(sheet.getStatus()) || "PUBLISHED".equals(sheet.getStatus())) {
            return;
        }
        List<Long> studentIds = studentCourseMarkRepository.findBySemesterSheetId(sheetId).stream()
                .map(s -> s.getStudent().getId())
                .distinct()
                .collect(java.util.stream.Collectors.toList());
        for (Long sId : studentIds) {
            syncSystemMarksForStudent(sheetId, sId, targetSectionId);
        }
    }

    @Transactional
    public MarkSheetSection addSection(Long sheetId, String sectionName, BigDecimal maxScore) {
        SemesterSheet sheet = getSheetById(sheetId);
        if (sheet == null || "SUBMITTED".equals(sheet.getStatus()) || "PUBLISHED".equals(sheet.getStatus())) {
            throw new RuntimeException("Cannot modify this sheet. It is not active or draft.");
        }

        BigDecimal currentTotal = sheet.getSections().stream()
            .map(MarkSheetSection::getMaxScore)
            .reduce(BigDecimal.ZERO, BigDecimal::add);
            
        if (currentTotal.add(maxScore).compareTo(new BigDecimal("100")) > 0) {
            throw new RuntimeException("Adding this section exceeds the maximum 100 total marks. Please delete or modify an existing section first.");
        }

        MarkSheetSection newSection = new MarkSheetSection();
        newSection.setSemesterSheet(sheet);
        newSection.setSectionName(sectionName);
        newSection.setMaxScore(maxScore);
        newSection.setDeletable(true);
        MarkSheetSection savedSection = markSheetSectionRepository.save(newSection);
        
        sheet.getSections().add(savedSection);
        semesterSheetRepository.save(sheet);

        // Inject new section into all existing student marks
        List<StudentCourseMark> scms = studentCourseMarkRepository.findBySemesterSheetId(sheetId);
        for (StudentCourseMark scm : scms) {
            StudentSectionMark ssm = new StudentSectionMark();
            ssm.setStudentCourseMark(scm);
            ssm.setSection(savedSection);
            ssm.setScoreObtained(BigDecimal.ZERO);
            studentSectionMarkRepository.save(ssm);
        }

        return savedSection;
    }

    @Transactional
    public void deleteSection(Long sheetId, Long sectionId) {
        SemesterSheet sheet = getSheetById(sheetId);
        if (sheet == null || "SUBMITTED".equals(sheet.getStatus()) || "PUBLISHED".equals(sheet.getStatus())) {
            throw new RuntimeException("Cannot modify this sheet. It is not active or draft.");
        }

        MarkSheetSection sectionToRemove = markSheetSectionRepository.findById(sectionId).orElse(null);
        if (sectionToRemove == null || !sectionToRemove.getSemesterSheet().getId().equals(sheetId)) {
            return; // invalid
        }

        if (!sectionToRemove.isDeletable()) {
            throw new RuntimeException("This section is marked as required and cannot be deleted.");
        }

        // Remove all StudentSectionMarks linked to this section
        List<StudentCourseMark> scms = studentCourseMarkRepository.findBySemesterSheetId(sheetId);
        for (StudentCourseMark scm : scms) {
            List<StudentSectionMark> marksToRemove = scm.getSectionMarks().stream()
                .filter(ssm -> ssm.getSection().getId().equals(sectionId))
                .collect(java.util.stream.Collectors.toList());
            
            for (StudentSectionMark ssm : marksToRemove) {
                scm.getSectionMarks().remove(ssm);
                studentSectionMarkRepository.delete(ssm);
            }

            // Recalculate totals
            BigDecimal total = BigDecimal.ZERO;
            for (StudentSectionMark ssm : scm.getSectionMarks()) {
                total = total.add(ssm.getScoreObtained() != null ? ssm.getScoreObtained() : BigDecimal.ZERO);
            }
            scm.setTotalScore(total);
            scm.setGrade(calculateGrade(total));
            studentCourseMarkRepository.save(scm);
        }

        sheet.getSections().remove(sectionToRemove);
        semesterSheetRepository.save(sheet);
        markSheetSectionRepository.delete(sectionToRemove);
    }


    /**
     * Returns marks for the given student from the given sheet.
     * Strategy:
     *  1. If pre-generated rows exist → return them directly.
     *  2. If no rows exist → try to auto-create them from the sheet's courses.
     *  3. If still no rows (no courses linked) → still return a valid DTO with empty courseMarks
     *     so the frontend can show the student's name and a "pending" state.
     *  4. Only return null if the sheet itself doesn't exist or the student doesn't exist.
     */
    @Transactional
    public com.exam.DTO.SemesterSheetDTO.StudentMarkDTO getStudentMarks(Long sheetId, Long studentId) {
        List<StudentCourseMark> courseMarks = studentCourseMarkRepository.findBySemesterSheetIdAndStudentId(sheetId, studentId);

        SemesterSheet sheet = getSheetById(sheetId);
        if (sheet == null) return null;

        User student = userRepository.findById(studentId).orElse(null);
        if (student == null) return null;

        // If no rows exist, try to auto-create them
        if (courseMarks.isEmpty()) {
            Program program = sheet.getProgram();
            String level = sheet.getLevel();
            Integer semester = sheet.getSemester();

            // Try to find courses with multiple level-format fallbacks
            List<Category> courses = new ArrayList<>();
            if (program != null) {
                courses = categoryRepository.findByProgramsContainingAndLevelAndSemester(program, level, semester);
                if (courses.isEmpty() && level != null) {
                    courses = categoryRepository.findByProgramsContainingAndLevelAndSemester(program, "Level " + level, semester);
                }
                if (courses.isEmpty()) {
                    courses = categoryRepository.findByProgramsContaining(program).stream()
                        .filter(c -> semester != null && semester.equals(c.getSemester()))
                        .collect(java.util.stream.Collectors.toList());
                }
                if (courses.isEmpty()) {
                    // Final fallback: all courses for this program, any semester
                    courses = categoryRepository.findByProgramsContaining(program);
                }
            }

            // Create blank mark rows for each course
            for (Category course : courses) {
                StudentCourseMark scm = new StudentCourseMark();
                scm.setSemesterSheet(sheet);
                scm.setStudent(student);
                scm.setCourse(course);
                scm.setTotalScore(BigDecimal.ZERO);
                scm.setGrade("N/A");
                for (MarkSheetSection sec : sheet.getSections()) {
                    StudentSectionMark ssm = new StudentSectionMark();
                    ssm.setStudentCourseMark(scm);
                    ssm.setSection(sec);
                    ssm.setScoreObtained(BigDecimal.ZERO);
                    scm.getSectionMarks().add(ssm);
                }
                studentCourseMarkRepository.save(scm);
            }

            // Re-fetch after auto-provisioning
            courseMarks = studentCourseMarkRepository.findBySemesterSheetIdAndStudentId(sheetId, studentId);
        }

        // Build the DTO — always succeed even if courseMarks is still empty
        com.exam.DTO.SemesterSheetDTO.StudentMarkDTO dto = new com.exam.DTO.SemesterSheetDTO.StudentMarkDTO();
        dto.setStudentId(student.getId());
        dto.setStudentName(student.getFirstname() + " " + student.getLastname());
        dto.setUsername(student.getUsername());
        dto.setCourseMarks(new ArrayList<>());

        for (StudentCourseMark scm : courseMarks) {
            com.exam.DTO.SemesterSheetDTO.CourseMarkDTO cmDto = new com.exam.DTO.SemesterSheetDTO.CourseMarkDTO();
            cmDto.setCourseMarkId(scm.getId());
            cmDto.setCourseId(scm.getCourse().getCid());
            cmDto.setCourseTitle(scm.getCourse().getTitle());
            cmDto.setCourseCode(scm.getCourse().getCourseCode());
            cmDto.setTotalScore(scm.getTotalScore());
            cmDto.setGrade(scm.getGrade());

            List<com.exam.DTO.SemesterSheetDTO.SectionMarkDTO> sectionMarkDTOs = new ArrayList<>();
            for (StudentSectionMark ssm : scm.getSectionMarks()) {
                com.exam.DTO.SemesterSheetDTO.SectionMarkDTO ssmDto = new com.exam.DTO.SemesterSheetDTO.SectionMarkDTO();
                ssmDto.setSectionMarkId(ssm.getId());
                ssmDto.setSectionId(ssm.getSection().getId());
                ssmDto.setScoreObtained(ssm.getScoreObtained());
                sectionMarkDTOs.add(ssmDto);
            }
            cmDto.setSectionMarks(sectionMarkDTOs);
            dto.getCourseMarks().add(cmDto);
        }
        return dto;
    }
}
