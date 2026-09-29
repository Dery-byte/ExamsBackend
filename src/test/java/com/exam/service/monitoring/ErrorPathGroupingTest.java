package com.exam.service.monitoring;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class ErrorPathGroupingTest {

    @Test
    void idsAreFoldedSoTheSameFailureGroupsTogether() {
        assertThat(ErrorMonitorService.normalisePath("/api/remarks/42/respond")).isEqualTo("/api/remarks/{id}/respond");
        assertThat(ErrorMonitorService.normalisePath("/api/marks/sheet/7")).isEqualTo("/api/marks/sheet/{id}");
        assertThat(ErrorMonitorService.normalisePath("/q/3f2b1c9a-1234-4bcd-9abc-0123456789ab.webp?x=1"))
                .isEqualTo("/q/3f2b1c9a-1234-4bcd-9abc-0123456789ab.webp");
        assertThat(ErrorMonitorService.normalisePath("/api/v1/auth/level/200/semester/2/pdf"))
                .isEqualTo("/api/v1/auth/level/{id}/semester/{id}/pdf");
    }
}
