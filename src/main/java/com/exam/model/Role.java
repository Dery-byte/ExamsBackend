package com.exam.model;

public enum Role {
  SUPER_ADMIN,
  ADMIN,
  LECTURER,
  NORMAL,
  /** Signs in with an emailed code; only sets the system mode and watches system health. */
  DEVELOPER
}
