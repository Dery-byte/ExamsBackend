package com.exam.model.comms;

/** Which roles an announcement is addressed to. */
public enum AnnouncementAudience {
    ALL,        // everyone
    STUDENTS,
    LECTURERS,
    ADMINS,     // HODs
    STAFF       // lecturers + HODs
}
