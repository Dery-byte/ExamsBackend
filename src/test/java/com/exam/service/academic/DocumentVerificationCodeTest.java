package com.exam.service.academic;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class DocumentVerificationCodeTest {

    @Test
    void codesTypedLooselyAreNormalised() {
        assertThat(DocumentVerificationService.normalise(" abcd efgh-jkmn ")).isEqualTo("ABCD-EFGH-JKMN");
        assertThat(DocumentVerificationService.normalise("ABCDEFGHJKMN")).isEqualTo("ABCD-EFGH-JKMN");
        assertThat(DocumentVerificationService.normalise("ABCD-EFGH")).isNull();
        assertThat(DocumentVerificationService.normalise(null)).isNull();
    }
}
