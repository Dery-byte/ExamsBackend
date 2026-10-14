package com.exam.model.attendance;

/**
 * How the lecturer shows the check-in code.
 * PROJECTOR: a short code on a screen, replaced every few seconds.
 * READ_ALOUD (rooms without a screen): a longer-lived code read out; every check-in then waits for the lecturer.
 */
public enum CodeDisplay { PROJECTOR, READ_ALOUD }
