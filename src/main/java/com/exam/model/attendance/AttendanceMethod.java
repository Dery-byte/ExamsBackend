package com.exam.model.attendance;

/**
 * How a meeting's attendance is taken, chosen by the lecturer when starting it.
 * STUDENT_CODE: students type the code shown (or read out) on their own phones.
 * STAFF_SCAN: each student shows a personal QR code on their phone and the lecturer (or a helper)
 * scans it, so nobody is marked present without being seen.
 */
public enum AttendanceMethod { STUDENT_CODE, STAFF_SCAN }
