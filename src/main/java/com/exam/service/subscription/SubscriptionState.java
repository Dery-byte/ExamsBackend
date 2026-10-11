package com.exam.service.subscription;

/** Where the institution's subscription stands; each step takes away more of the portal. */
public enum SubscriptionState {
    /** Paid up. Everything works. */
    ACTIVE,
    /** Payment overdue. Everything still works; the Super Admin (and Admins, if chosen) see a warning. */
    GRACE,
    /** Read-only: people can still sign in, view and export, but nothing new can be created or changed. */
    RESTRICTED,
    /** Only the developer and the Super Admin (read-only, to export data) can use the portal. */
    SUSPENDED
}
