package com.daman.admin.dto;

import com.daman.admin.entity.TrialRegistration;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;

class TrialRegistrationDtoTest {

    static final LocalDateTime REG = LocalDateTime.of(2026, 10, 10, 12, 0);

    private static TrialRegistration row(LocalDateTime converted) {
        TrialRegistration t = new TrialRegistration();
        t.setClientCode("c-1");
        t.setShopName("Shop");
        t.setRegisteredAt(REG);
        t.setExpiresAt(REG.plusDays(7));
        t.setConvertedAt(converted);
        return t;
    }

    @Test
    void dayNumberAndLengthComeFromTheRowTimestamps() {
        var dto = TrialRegistrationDto.of(row(null), REG.plusDays(2).plusHours(1));
        assertThat(dto.dayNumber()).isEqualTo(3);
        assertThat(dto.totalDays()).isEqualTo(7);
        assertThat(dto.state()).isEqualTo("ACTIVE");
    }

    @Test
    void dayNumberIsCappedAtTheTrialLengthAndStateIsExpired() {
        var dto = TrialRegistrationDto.of(row(null), REG.plusDays(30));
        assertThat(dto.dayNumber()).isEqualTo(7);
        assertThat(dto.state()).isEqualTo("EXPIRED");
    }

    @Test
    void convertedWinsOverExpired() {
        var dto = TrialRegistrationDto.of(row(REG.plusDays(9)), REG.plusDays(30));
        assertThat(dto.state()).isEqualTo("CONVERTED");
    }

    @Test
    void dayNumberNeverBelowOne() {
        assertThat(TrialRegistrationDto.of(row(null), REG.minusDays(1)).dayNumber()).isEqualTo(1);
    }

    @Test
    void stillActiveThroughoutTheExpiryDateLikeTheService() {
        // expiresAt is midnight at the START of the last day; the service/desktop treat the trial as live all that day.
        assertThat(TrialRegistrationDto.of(row(null), REG.plusDays(7).withHour(0).withMinute(0).plusHours(15)).state())
                .isEqualTo("ACTIVE");
        assertThat(TrialRegistrationDto.of(row(null), REG.plusDays(8)).state()).isEqualTo("EXPIRED");
    }
}
