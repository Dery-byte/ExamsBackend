package com.exam.model.attendance;

/**
 * How a meeting's attendance is taken, chosen by the lecturer when starting it.
 * STUDENT_CODE: students type the code shown (or read out) on their own phones.
 * STAFF_SCAN: each student shows a personal QR code on their phone and the lecturer (or a helper)
 * scans it, so nobody is marked present without being seen.
 * STUDENT_SCAN: the lecturer (or a helper) shows the class's changing code as a QR code and students
 * scan it with their phones; otherwise it works like STUDENT_CODE (same code, expiry and checks).
 */
public enum AttendanceMethod { STUDENT_CODE, STAFF_SCAN, STUDENT_SCAN }
