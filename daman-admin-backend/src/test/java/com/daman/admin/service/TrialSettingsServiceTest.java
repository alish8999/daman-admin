package com.daman.admin.service;

import com.daman.admin.entity.AppSetting;
import com.daman.admin.repository.AppSettingRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class TrialSettingsServiceTest {

    private AppSettingRepository repo;
    private TrialSettingsService settings;

    @BeforeEach
    void setUp() {
        repo = mock(AppSettingRepository.class);
        when(repo.findBySettingKey(org.mockito.ArgumentMatchers.anyString())).thenReturn(Optional.empty());
        settings = new TrialSettingsService(repo);
    }

    private void given(String key, String value) {
        AppSetting s = new AppSetting();
        s.setSettingKey(key);
        s.setValueJson(value);
        when(repo.findBySettingKey(key)).thenReturn(Optional.of(s));
    }

    @Test
    void defaultsApplyWhenNoSettingRowExists() {
        assertThat(settings.days()).isEqualTo(7);
        assertThat(settings.enabled()).isTrue();
        assertThat(settings.graceDays()).isEqualTo(2);
        assertThat(settings.presetsJson()).isNull();
    }

    @Test
    void storedValuesOverrideDefaults() {
        given("trial.days", "14");
        given("trial.graceDays", "5");
        given("trial.presets", "{\"a\":1}");
        assertThat(settings.days()).isEqualTo(14);
        assertThat(settings.graceDays()).isEqualTo(5);
        assertThat(settings.presetsJson()).isEqualTo("{\"a\":1}");
    }

    @Test
    void unparseableValueFallsBackToTheDefaultAndLogs() {
        given("trial.days", "not-a-number");
        assertThat(settings.days()).isEqualTo(7);
    }

    @Test
    void blankDaysFallsBackToTheDefault() {
        given("trial.days", "  ");
        assertThat(settings.days()).isEqualTo(7);
    }

    @Test
    void nonPositiveDaysFallsBackToTheDefault() {
        given("trial.days", "0");
        assertThat(settings.days()).isEqualTo(7);
        given("trial.days", "-3");
        assertThat(settings.days()).isEqualTo(7);
    }

    @Test
    void negativeGraceFallsBackButZeroGraceIsAllowed() {
        given("trial.graceDays", "-1");
        assertThat(settings.graceDays()).isEqualTo(2);
        given("trial.graceDays", "0");
        assertThat(settings.graceDays()).isEqualTo(0);
    }

    @Test
    void enabledIsFalseOnlyForAnExplicitFalse() {
        given("trial.enabled", "false");
        assertThat(settings.enabled()).isFalse();
        given("trial.enabled", "");
        assertThat(settings.enabled()).isTrue();
        given("trial.enabled", "garbage");
        assertThat(settings.enabled()).isTrue();
    }

    @Test
    void blankPresetsIsNull() {
        given("trial.presets", " ");
        assertThat(settings.presetsJson()).isNull();
    }
}
