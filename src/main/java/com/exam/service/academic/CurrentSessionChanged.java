package com.exam.service.academic;

/** Published when another session is made current; previousId is null when none was current. */
public record CurrentSessionChanged(Long previousId, Long currentId) {}
