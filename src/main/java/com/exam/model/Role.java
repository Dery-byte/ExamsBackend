package com.exam.model;

public enum Role {
  SUPER_ADMIN,
  ADMIN,
  LECTURER,
  NORMAL,
  /** Signs in with an emailed code; only sets the system mode, watches system health and reads the audit log. */
  DEVELOPER
}
