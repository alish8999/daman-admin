package com.daman.admin.service;

import com.daman.admin.dto.TrialRegisterRequest;
import com.daman.admin.dto.TrialRegisterResponse;
import com.daman.admin.entity.ClientConfig;
import com.daman.admin.entity.License;
import com.daman.admin.entity.TrialRegistration;
import com.daman.admin.repository.ClientConfigRepository;
import com.daman.admin.repository.LicenseRepository;
import com.daman.admin.repository.TrialRegistrationRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.ObjectMapper;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * Registration is a PUBLIC, unauthenticated endpoint that creates rows and mints a signed
 * licence, so every "nothing was created" assertion here is {@code verify(repo, never()).save}
 * - a mock's count() is 0 whatever the code did, which would make a count assertion vacuous.
 */
class TrialRegistrationServiceTest {

    private TrialSettingsService settings;
    private LicenseKeyService keys;
    private ClientConfigRepository clients;
    private TrialRegistrationRepository trials;
    private LicenseRepository licenses;
    private TrialRegistrationService service;

    @BeforeEach
    void setUp() {
        settings = mock(TrialSettingsService.class);
        when(settings.enabled()).thenReturn(true);
        when(settings.days()).thenReturn(7);
        when(settings.presetsJson()).thenReturn(null);
        keys = mock(LicenseKeyService.class);
        when(keys.generateLicense(anyString(), anyString(), anyString(), anyString(), anyString(), any(),
                anyString(), anyString(), anyString(), anyString(), any(), eq(true))).thenReturn("KEY-1");
        clients = mock(ClientConfigRepository.class);
        when(clients.findClientCodesByPrefix("c-")).thenReturn(List.of());
        trials = mock(TrialRegistrationRepository.class);
        when(trials.findByMachineId(anyString())).thenReturn(Optional.empty());
        licenses = mock(LicenseRepository.class);
        TransactionTemplate tx = new TransactionTemplate(mock(PlatformTransactionManager.class));
        service = new TrialRegistrationService(settings,
                new TrialPresetService(settings, new ObjectMapper()), keys, clients, trials, licenses, tx,
                new ObjectMapper());
    }

    private static TrialRegisterRequest req() {
        return new TrialRegisterRequest("M1", "Win11/HP", "Corner Cafe", "0999", "Main St", "cafe", true, "SYP", null);
    }


    private void assertNothingCreated() {
        verify(clients, never()).save(any());
        verify(trials, never()).save(any());
        verify(licenses, never()).save(any());
    }

    @Test
    void registeringCreatesClientRegistrationAndLicence() {
        TrialRegisterResponse res = service.register(req(), "M1", "Win11/HP");

        assertThat(res.licenseKey()).isEqualTo("KEY-1");

        ArgumentCaptor<ClientConfig> c = ArgumentCaptor.forClass(ClientConfig.class);
        verify(clients).save(c.capture());
        assertThat(c.getValue().getClientCode()).isEqualTo("c-1");
        assertThat(c.getValue().getAppName()).isEqualTo("Corner Cafe");
        assertThat(c.getValue().getClientStatus()).isEqualTo("TRIAL");

        ArgumentCaptor<TrialRegistration> r = ArgumentCaptor.forClass(TrialRegistration.class);
        verify(trials).save(r.capture());
        assertThat(r.getValue().getMachineId()).isEqualTo("M1");
        assertThat(r.getValue().getClientCode()).isEqualTo("c-1");
        assertThat(r.getValue().getExpiresAt().toLocalDate()).isEqualTo(LocalDate.now().plusDays(7));

        ArgumentCaptor<License> l = ArgumentCaptor.forClass(License.class);
        verify(licenses).save(l.capture());
        assertThat(l.getValue().getLabel()).isEqualTo("TRIAL");
        assertThat(l.getValue().getStatus()).isEqualTo("ACTIVE");
        assertThat(l.getValue().getLicenseKey()).isEqualTo("KEY-1");
        assertThat(l.getValue().getMachineId()).isEqualTo("M1");
        assertThat(l.getValue().getClientCode()).isEqualTo("c-1");
        assertThat(l.getValue().getExpiresAt()).isEqualTo(LocalDate.now().plusDays(7));
    }

    @Test
    @SuppressWarnings("unchecked")
    void storedFeaturesMatchTheMintedLicenceSoLaterMintsKeepThem() {
        service.register(req(), "M1", "d");

        ArgumentCaptor<Map<String, Boolean>> minted = ArgumentCaptor.forClass(Map.class);
        verify(keys).generateLicense(anyString(), anyString(), anyString(), anyString(), anyString(),
                minted.capture(), anyString(), anyString(), anyString(), anyString(), any(), eq(true));
        ArgumentCaptor<ClientConfig> c = ArgumentCaptor.forClass(ClientConfig.class);
        verify(clients).save(c.capture());

        ClientConfigRepository repo = mock(ClientConfigRepository.class);
        when(repo.findByClientCode("c-1")).thenReturn(Optional.of(c.getValue()));
        Map<String, Boolean> later = new ClientConfigService(repo, new ObjectMapper())
                .licenseEntitlementsFor("c-1").features();

        assertThat(later).containsEntry("cafeMode", true).containsEntry("tableOrders", true);
        // The preset map may omit flags that are false; every flag it does name must survive unchanged.
        assertThat(later).containsAllEntriesOf(minted.getValue());
    }

    @Test
    void theMintHappensBeforeAnythingIsPersisted() {
        service.register(req(), "M1", "d");

        InOrder order = inOrder(keys, clients, trials, licenses);
        order.verify(keys).generateLicense(anyString(), anyString(), anyString(), anyString(), anyString(),
                any(), anyString(), anyString(), anyString(), anyString(), any(), eq(true));
        order.verify(clients).save(any());
        order.verify(trials).save(any());
        order.verify(licenses).save(any());
    }

    @Test
    void failedMintLeavesNoTraceByConstruction() {
        when(keys.generateLicense(anyString(), anyString(), anyString(), anyString(), anyString(), any(),
                anyString(), anyString(), anyString(), anyString(), any(), eq(true)))
                .thenThrow(new IllegalStateException("signing failed"));

        assertThatThrownBy(() -> service.register(req(), "M1", "d")).isInstanceOf(IllegalStateException.class);

        assertNothingCreated();
    }

    @Test
    void clientCodeIncrementsFromTheHighestExistingTrialCode() {
        when(clients.findClientCodesByPrefix("c-")).thenReturn(List.of("c-2", "c-10", "c-x", "c-9"));

        service.register(req(), "M1", "d");

        ArgumentCaptor<ClientConfig> c = ArgumentCaptor.forClass(ClientConfig.class);
        verify(clients).save(c.capture());
        assertThat(c.getValue().getClientCode()).isEqualTo("c-11");
    }

    @Test
    void theProfileFieldsOnlyThisPathCanWriteReachTheClientRow() {
        service.register(req(), "M1", "d");

        ArgumentCaptor<ClientConfig> c = ArgumentCaptor.forClass(ClientConfig.class);
        verify(clients).save(c.capture());
        assertThat(c.getValue().getAddress()).isEqualTo("Main St");
        assertThat(c.getValue().getHasTables()).isTrue();
        assertThat(c.getValue().getPhone()).isEqualTo("0999");
        assertThat(c.getValue().getStoreType()).isEqualTo("cafe");
        assertThat(c.getValue().getBaseCurrency()).isEqualTo("SYP");
        assertThat(c.getValue().getLogoDark()).isNotNull();
        assertThat(c.getValue().getLogoLight()).isNotNull();
        assertThat(c.getValue().getTagline()).isNotBlank();
        assertThat(c.getValue().getColorPrimary()).isNotBlank();
        assertThat(c.getValue().getColorSecondary()).isNotBlank();
    }

    @Test
    void registrationRowMirrorsTheProfile() {
        service.register(req(), "M1", "d");

        ArgumentCaptor<TrialRegistration> r = ArgumentCaptor.forClass(TrialRegistration.class);
        verify(trials).save(r.capture());
        assertThat(r.getValue().getShopName()).isEqualTo("Corner Cafe");
        assertThat(r.getValue().getPhone()).isEqualTo("0999");
        assertThat(r.getValue().getAddress()).isEqualTo("Main St");
        assertThat(r.getValue().getStoreType()).isEqualTo("cafe");
        assertThat(r.getValue().getHasTables()).isTrue();
        assertThat(r.getValue().getBaseCurrency()).isEqualTo("SYP");
        assertThat(r.getValue().getRegisteredAt()).isNotNull();
    }

    @Test
    void secondCallFromTheSameMachineReturnsTheSameKeyAndCreatesNothing() {
        when(trials.findByMachineId("M1")).thenReturn(
                Optional.of(registration("c-1", "M1", LocalDateTime.now().plusDays(3))));
        License lic = new License();
        lic.setLicenseKey("KEY-ORIGINAL");
        when(licenses.findAllByMachineIdAndClientCodeAndStatusOrderByIdDesc("M1", "c-1", "ACTIVE")).thenReturn(List.of(lic));

        assertThat(service.register(req(), "M1", "d").licenseKey()).isEqualTo("KEY-ORIGINAL");

        assertNothingCreated();
        verifyNoInteractions(keys);
    }

    @Test
    void aLiveRegistrationWhoseLicenceIsGoneIsRefusedNotReminted() {
        when(trials.findByMachineId("M1")).thenReturn(
                Optional.of(registration("c-1", "M1", LocalDateTime.now().plusDays(3))));
        when(licenses.findAllByMachineIdAndClientCodeAndStatusOrderByIdDesc("M1", "c-1", "ACTIVE")).thenReturn(List.of());

        assertThatThrownBy(() -> service.register(req(), "M1", "d")).isInstanceOf(TrialAlreadyUsedException.class);

        assertNothingCreated();
        verifyNoInteractions(keys);
    }

    @Test
    void anExpiredTrialIsRefusedAndGrantsNothing() {
        when(trials.findByMachineId("M1")).thenReturn(
                Optional.of(registration("c-1", "M1", LocalDate.now().minusDays(1).atStartOfDay())));

        assertThatThrownBy(() -> service.register(req(), "M1", "d")).isInstanceOf(TrialAlreadyUsedException.class);

        assertNothingCreated();
        verifyNoInteractions(keys);
        // CHANGED 2026-10-21: this used to assert the licence lookup was never reached, which
        // pinned the very bug behind "it asks me to fill the form again" - gating on the trial's
        // original window meant a client with a perfectly good licence was refused without ever
        // being looked up. The lookup now runs first; with no licence stubbed it finds nothing
        // usable and the refusal still stands, which is what this test is really about.
        verify(licenses).findAllByMachineIdAndClientCodeAndStatusOrderByIdDesc("M1", "c-1", "ACTIVE");
    }

    @Test
    void disabledTrialsCreateNothingAndMintNothing() {
        when(settings.enabled()).thenReturn(false);

        assertThatThrownBy(() -> service.register(req(), "M1", "d")).isInstanceOf(TrialsDisabledException.class);

        assertNothingCreated();
        verifyNoInteractions(keys);
    }

    @Test
    void disabledTrialsAlsoRefuseAMachineThatAlreadyHasOne() {
        when(settings.enabled()).thenReturn(false);
        when(trials.findByMachineId("M1")).thenReturn(
                Optional.of(registration("c-1", "M1", LocalDateTime.now().plusDays(3))));

        assertThatThrownBy(() -> service.register(req(), "M1", "d")).isInstanceOf(TrialsDisabledException.class);
        verifyNoInteractions(keys);
    }

    @Test
    void expiryComesFromSettingsNotFromTheRequest() {
        when(settings.days()).thenReturn(14);

        service.register(req(), "M1", "d");

        ArgumentCaptor<TrialRegistration> r = ArgumentCaptor.forClass(TrialRegistration.class);
        verify(trials).save(r.capture());
        assertThat(r.getValue().getExpiresAt().toLocalDate()).isEqualTo(LocalDate.now().plusDays(14));
        ArgumentCaptor<String> expiry = ArgumentCaptor.forClass(String.class);
        verify(keys).generateLicense(anyString(), anyString(), anyString(), expiry.capture(), anyString(),
                any(), anyString(), anyString(), anyString(), anyString(), any(), eq(true));
        assertThat(expiry.getValue()).isEqualTo(LocalDate.now().plusDays(14).toString());
    }

    @Test
    void aDaysFieldInTheRequestBodyIsIgnored() throws Exception {
        String json = "{\"machineId\":\"M1\",\"shopName\":\"X\",\"storeType\":\"general\",\"baseCurrency\":\"USD\","
                + "\"days\":999,\"trialDays\":999,\"expiresAt\":\"2099-01-01\",\"clientCode\":\"daman-1\"}";
        TrialRegisterRequest parsed = new ObjectMapper().readValue(json, TrialRegisterRequest.class);

        service.register(parsed, "M1", "d");

        ArgumentCaptor<TrialRegistration> r = ArgumentCaptor.forClass(TrialRegistration.class);
        verify(trials).save(r.capture());
        assertThat(r.getValue().getExpiresAt().toLocalDate()).isEqualTo(LocalDate.now().plusDays(7));
        assertThat(r.getValue().getClientCode()).isEqualTo("c-1");
    }

    /**
     * REVERSED 2026-10-21 on the owner's instruction. This asserted the opposite - a random
     * per-trial password of at least 12 characters, explicitly not "123456" - because a
     * shared default is normally indefensible. For a TRIAL it is a deliberate trade: the
     * prospect is evaluating the app on their own empty install and should not have to be
     * read a random string before they can open Settings. The risk is not the trial, it is
     * a shop that converts to paid and never changes it; see TRIAL_ADMIN_PASSWORD.
     */
    @Test
    void everyTrialIsSeededWithTheSameKnownAdminPassword() {
        service.register(req(), "M1", "d");
        service.register(req(), "M2", "d");

        ArgumentCaptor<ClientConfig> c = ArgumentCaptor.forClass(ClientConfig.class);
        verify(clients, times(2)).save(c.capture());
        assertThat(c.getAllValues().get(0).getAdminPassword()).isEqualTo("123456");
        assertThat(c.getAllValues().get(1).getAdminPassword()).isEqualTo("123456");
        // and that same password is what the licence payload carries
        ArgumentCaptor<String> pw = ArgumentCaptor.forClass(String.class);
        verify(keys, times(2)).generateLicense(anyString(), anyString(), anyString(), anyString(), anyString(),
                any(), anyString(), anyString(), anyString(), pw.capture(), any(), eq(true));
        assertThat(pw.getAllValues().get(0)).isEqualTo("123456");
    }

    @Test
    @SuppressWarnings("unchecked")
    void theMintedPayloadIsBoundToTheCallersMachineAndCarriesTheProfileAndPresetFeatures() {
        service.register(req(), "M1", "d");

        ArgumentCaptor<String> machine = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<Map<String, Boolean>> features = ArgumentCaptor.forClass(Map.class);
        ArgumentCaptor<LicenseProfile> profile = ArgumentCaptor.forClass(LicenseProfile.class);
        verify(keys).generateLicense(machine.capture(), eq("Corner Cafe"), eq("c-1"), anyString(), eq("SYP"),
                features.capture(), anyString(), anyString(), eq("admin"), anyString(), profile.capture(), eq(true));
        assertThat(machine.getValue()).isEqualTo("M1");
        assertThat(features.getValue()).isEqualTo(
                new TrialPresetService(settings, new ObjectMapper()).featuresFor("cafe", true));
        assertThat(profile.getValue()).isEqualTo(new LicenseProfile("Corner Cafe", "0999", "Main St", "cafe", true));
    }

    @Test
    void aNullHasTablesMeansNoTablesAndIsStoredAsFalse() {
        service.register(new TrialRegisterRequest("M1", "d", "Shop", null, null, "cafe", null, "USD", null), "M1", "d");

        ArgumentCaptor<ClientConfig> c = ArgumentCaptor.forClass(ClientConfig.class);
        verify(clients).save(c.capture());
        assertThat(c.getValue().getHasTables()).isFalse();
    }

    @Test
    void overlongFieldsAreTruncated() {
        String big = "x".repeat(5000);
        service.register(new TrialRegisterRequest("M1", big, big, big, big, "general", false, "USD", null), "M1", big);

        ArgumentCaptor<ClientConfig> c = ArgumentCaptor.forClass(ClientConfig.class);
        verify(clients).save(c.capture());
        assertThat(c.getValue().getAppName()).hasSizeLessThanOrEqualTo(100);
        assertThat(c.getValue().getPhone()).hasSizeLessThanOrEqualTo(50);
        assertThat(c.getValue().getAddress()).hasSizeLessThanOrEqualTo(255);
        ArgumentCaptor<License> l = ArgumentCaptor.forClass(License.class);
        verify(licenses).save(l.capture());
        assertThat(l.getValue().getDeviceInfo()).hasSizeLessThanOrEqualTo(255);
    }

    static Stream<TrialRegisterRequest> invalidRequests() {
        return Stream.of(
                new TrialRegisterRequest("M1", "d", "Shop", "1", "a", "cafe", true, "EUR", null),
                new TrialRegisterRequest("M1", "d", "Shop", "1", "a", "cafe", true, null, null),
                new TrialRegisterRequest("M1", "d", "Shop", "1", "a", "taxidermist", true, "USD", null),
                new TrialRegisterRequest("M1", "d", "Shop", "1", "a", null, true, "USD", null),
                new TrialRegisterRequest("M1", "d", "   ", "1", "a", "cafe", true, "USD", null),
                new TrialRegisterRequest("M1", "d", null, "1", "a", "cafe", true, "USD", null));
    }

    @ParameterizedTest
    @MethodSource("invalidRequests")
    void invalidInputIsRejectedAndCreatesNothing(TrialRegisterRequest bad) {
        assertThatThrownBy(() -> service.register(bad, "M1", "d")).isInstanceOf(IllegalArgumentException.class);

        assertNothingCreated();
        verifyNoInteractions(keys);
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {"   "})
    void aMissingMachineIdIsRejected(String machineId) {
        assertThatThrownBy(() -> service.register(req(), machineId, "d")).isInstanceOf(IllegalArgumentException.class);

        assertNothingCreated();
        verifyNoInteractions(keys);
    }

    @Test
    void anOverlongMachineIdIsRejectedNotTruncated() {
        assertThatThrownBy(() -> service.register(req(), "m".repeat(129), "d"))
                .isInstanceOf(IllegalArgumentException.class);
        assertNothingCreated();
    }

    @Test
    void currencyAndStoreTypeAreNormalised() {
        service.register(new TrialRegisterRequest("M1", "d", "Shop", null, null, " Cafe ", true, " syp ", null), "M1", "d");

        ArgumentCaptor<ClientConfig> c = ArgumentCaptor.forClass(ClientConfig.class);
        verify(clients).save(c.capture());
        assertThat(c.getValue().getBaseCurrency()).isEqualTo("SYP");
        assertThat(c.getValue().getStoreType()).isEqualTo("cafe");
    }

    @Test
    void twoRacingRegistrationsBothEndUpWithTheWinnersKey() {
        // We lose the race: the unique index on machineId rejects our registration insert,
        // and the winner's rows are already committed by the time we re-read.
        when(trials.save(any())).thenThrow(new DataIntegrityViolationException("uk_machine_id"));
        TrialRegistration winner = registration("c-1", "M1", LocalDateTime.now().plusDays(7));
        when(trials.findByMachineId("M1")).thenReturn(Optional.empty(), Optional.of(winner));
        License lic = new License();
        lic.setLicenseKey("KEY-WINNER");
        when(licenses.findAllByMachineIdAndClientCodeAndStatusOrderByIdDesc("M1", "c-1", "ACTIVE")).thenReturn(List.of(lic));

        assertThat(service.register(req(), "M1", "d").licenseKey()).isEqualTo("KEY-WINNER");
        verify(licenses, never()).save(any());
    }

    @Test
    void aClientCodeCollisionRetriesWithAFreshCodeAndReMints() {
        when(clients.save(any())).thenThrow(new DataIntegrityViolationException("uk_client_code"))
                .thenAnswer(i -> i.getArgument(0));
        when(clients.findClientCodesByPrefix("c-")).thenReturn(List.of(), List.of("c-1"));

        service.register(req(), "M1", "d");

        ArgumentCaptor<String> code = ArgumentCaptor.forClass(String.class);
        verify(keys, times(2)).generateLicense(anyString(), anyString(), code.capture(), anyString(), anyString(),
                any(), anyString(), anyString(), anyString(), anyString(), any(), eq(true));
        assertThat(code.getAllValues()).containsExactly("c-1", "c-2");
        ArgumentCaptor<License> l = ArgumentCaptor.forClass(License.class);
        verify(licenses).save(l.capture());
        assertThat(l.getValue().getClientCode()).isEqualTo("c-2");
    }

    @Test
    void aPersistentIntegrityFailureWithNoWinnerIsNotSwallowed() {
        when(clients.save(any())).thenThrow(new DataIntegrityViolationException("boom"));

        assertThatThrownBy(() -> service.register(req(), "M1", "d"))
                .isInstanceOf(DataIntegrityViolationException.class);
        verify(licenses, never()).save(any());
    }

    @Test
    void theResponseCarriesExactlyOneComponentNamedLicenseKey() {
        assertThat(TrialRegisterResponse.class.isRecord()).isTrue();
        assertThat(TrialRegisterResponse.class.getRecordComponents())
                .extracting(java.lang.reflect.RecordComponent::getName)
                .containsExactly("licenseKey");
    }

    private static TrialRegistration registration(String code, String machineId, LocalDateTime expires) {
        TrialRegistration t = new TrialRegistration();
        t.setClientCode(code);
        t.setMachineId(machineId);
        t.setShopName("Corner Cafe");
        t.setRegisteredAt(LocalDateTime.now().minusDays(1));
        t.setExpiresAt(expires);
        return t;
    }

    // ---- Task 5: refusal logging + newest-licence selection ----

    private ch.qos.logback.core.read.ListAppender<ch.qos.logback.classic.spi.ILoggingEvent> captureWarnings() {
        var appender = new ch.qos.logback.core.read.ListAppender<ch.qos.logback.classic.spi.ILoggingEvent>();
        appender.start();
        ((ch.qos.logback.classic.Logger) org.slf4j.LoggerFactory.getLogger(TrialRegistrationService.class))
                .addAppender(appender);
        return appender;
    }

    private static List<String> warnings(
            ch.qos.logback.core.read.ListAppender<ch.qos.logback.classic.spi.ILoggingEvent> a) {
        return a.list.stream()
                .filter(e -> e.getLevel() == ch.qos.logback.classic.Level.WARN)
                .map(ch.qos.logback.classic.spi.ILoggingEvent::getFormattedMessage)
                .toList();
    }

    @Test
    void anExpiredRefusalIsLoggedWithTheCauseMachineAndClientCode() {
        var logs = captureWarnings();
        when(trials.findByMachineId("M1")).thenReturn(
                Optional.of(registration("c-7", "M1", LocalDate.now().minusDays(1).atStartOfDay())));

        assertThatThrownBy(() -> service.register(req(), "M1", "d")).isInstanceOf(TrialAlreadyUsedException.class);

        assertThat(warnings(logs)).hasSize(1);
        assertThat(warnings(logs).get(0)).containsIgnoringCase("expired").contains("M1").contains("c-7");
    }

    @Test
    void aRevokedRefusalIsLoggedAsRevokedSoAnAdminCanFindIt() {
        var logs = captureWarnings();
        when(trials.findByMachineId("M1")).thenReturn(
                Optional.of(registration("c-7", "M1", LocalDateTime.now().plusDays(3))));
        when(licenses.findAllByMachineIdAndClientCodeAndStatusOrderByIdDesc("M1", "c-7", "ACTIVE"))
                .thenReturn(List.of());
        License revoked = new License();
        revoked.setClientCode("c-7");
        revoked.setMachineId("M1");
        revoked.setStatus("REVOKED");
        when(licenses.findByClientCodeOrderByActivatedAtDesc("c-7")).thenReturn(List.of(revoked));

        assertThatThrownBy(() -> service.register(req(), "M1", "d")).isInstanceOf(TrialAlreadyUsedException.class);

        assertThat(warnings(logs)).hasSize(1);
        assertThat(warnings(logs).get(0)).containsIgnoringCase("revoked").contains("M1").contains("c-7");
    }

    @Test
    void aMissingLicenceRefusalIsLoggedAsMissing() {
        var logs = captureWarnings();
        when(trials.findByMachineId("M1")).thenReturn(
                Optional.of(registration("c-7", "M1", LocalDateTime.now().plusDays(3))));
        when(licenses.findAllByMachineIdAndClientCodeAndStatusOrderByIdDesc("M1", "c-7", "ACTIVE"))
                .thenReturn(List.of());
        when(licenses.findByClientCodeOrderByActivatedAtDesc("c-7")).thenReturn(List.of());

        assertThatThrownBy(() -> service.register(req(), "M1", "d")).isInstanceOf(TrialAlreadyUsedException.class);

        assertThat(warnings(logs)).hasSize(1);
        assertThat(warnings(logs).get(0)).containsIgnoringCase("missing").contains("M1").contains("c-7");
        assertThat(warnings(logs).get(0)).doesNotContainIgnoringCase("revoked");
    }

    @Test
    void twoActiveLicencesForOneMachineReturnTheNewestKeyInsteadOfA500() {
        when(trials.findByMachineId("M1")).thenReturn(
                Optional.of(registration("c-1", "M1", LocalDateTime.now().plusDays(3))));
        License newest = new License();
        newest.setLicenseKey("KEY-NEWEST");
        License older = new License();
        older.setLicenseKey("KEY-OLDER");
        // Repository contract is newest-first; the service must take the head, not throw.
        when(licenses.findAllByMachineIdAndClientCodeAndStatusOrderByIdDesc("M1", "c-1", "ACTIVE"))
                .thenReturn(List.of(newest, older));

        assertThat(service.register(req(), "M1", "d").licenseKey()).isEqualTo("KEY-NEWEST");
        assertNothingCreated();
        verifyNoInteractions(keys);
    }

    // ── restoreOnly ────────────────────────────────────────────────────────────
    // The desktop asks "does this machine already have a trial?" so it can skip a form
    // whose values would be discarded. A restore must NEVER mint and must never validate.

    private static TrialRegisterRequest restoreReq() {
        // Everything an ordinary registration requires is absent or invalid on purpose: a
        // restore carries no form data, so validation must not be reached at all.
        return new TrialRegisterRequest("M1", "Win11/HP", null, null, null, null, null, null, true);
    }

    @Test
    void restoreOnlyReturnsTheExistingKeyWithoutAnyFormData() {
        TrialRegistration reg = registration("c-4", "M1", LocalDateTime.now().plusDays(3));
        when(trials.findByMachineId("M1")).thenReturn(Optional.of(reg));
        License lic = new License();
        lic.setLicenseKey("OLD-KEY");
        when(licenses.findAllByMachineIdAndClientCodeAndStatusOrderByIdDesc("M1", "c-4", "ACTIVE"))
                .thenReturn(List.of(lic));

        assertThat(service.register(restoreReq(), "M1", "Win11/HP").licenseKey()).isEqualTo("OLD-KEY");
        assertNothingCreated();
        verify(keys, never()).generateLicense(anyString(), anyString(), anyString(), anyString(), anyString(),
                any(), anyString(), anyString(), anyString(), anyString(), any(), anyBoolean());
    }

    @Test
    void restoreOnlyForAnUnknownMachineRefusesAndMintsNothing() {
        when(trials.findByMachineId("M1")).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.register(restoreReq(), "M1", "Win11/HP"))
                .isInstanceOf(NoTrialForMachineException.class);
        assertNothingCreated();
        verify(keys, never()).generateLicense(anyString(), anyString(), anyString(), anyString(), anyString(),
                any(), anyString(), anyString(), anyString(), anyString(), any(), anyBoolean());
    }

    @Test
    void restoreOnlyOnAnUnknownMachineIsNotReportedAsAValidationFailure() {
        // The body is deliberately unusable. Without the restoreOnly branch this would surface as
        // IllegalArgumentException ("shopName required") -> invalid_request, which would tell the
        // user to fix fields they never filled in. It must be the dedicated refusal instead.
        when(trials.findByMachineId("M1")).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.register(restoreReq(), "M1", "Win11/HP"))
                .isNotInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void restoreOnlyStillRefusesAnExpiredTrial() {
        when(trials.findByMachineId("M1")).thenReturn(
                Optional.of(registration("c-4", "M1", LocalDate.now().minusDays(1).atStartOfDay())));

        assertThatThrownBy(() -> service.register(restoreReq(), "M1", "Win11/HP"))
                .isInstanceOf(TrialAlreadyUsedException.class);
        assertNothingCreated();
    }

    @Test
    void anOrdinaryRegistrationIsUnaffectedByTheRestoreBranch() {
        // restoreOnly null and false must both mint exactly as before.
        for (Boolean flag : new Boolean[]{null, Boolean.FALSE}) {
            setUp();
            TrialRegisterResponse res = service.register(
                    new TrialRegisterRequest("M1", "d", "Corner Cafe", "0999", "Main St", "cafe", true, "SYP", flag),
                    "M1", "d");
            assertThat(res.licenseKey()).isEqualTo("KEY-1");
            verify(trials).save(any());
        }
    }

    // ── The current licence decides, not the original trial window ─────────────
    // Field report 2026-10-21: a shop that already has a licence was asked to fill the
    // trial form in again. The expiry gate ran on the TRIAL's original 7-day window and
    // threw before the licence lookup, so a converted or extended client could never get
    // its own key back.

    private static License licence(String key, LocalDate expires) {
        License l = new License();
        l.setLicenseKey(key);
        l.setExpiresAt(expires);
        l.setStatus("ACTIVE");
        return l;
    }

    @Test
    void aConvertedClientGetsItsPaidKeyBackLongAfterTheTrialWindowPassed() {
        when(trials.findByMachineId("M1")).thenReturn(
                Optional.of(registration("c-4", "M1", LocalDate.now().minusMonths(6).atStartOfDay())));
        when(licenses.findAllByMachineIdAndClientCodeAndStatusOrderByIdDesc("M1", "c-4", "ACTIVE"))
                .thenReturn(List.of(licence("PAID-KEY", LocalDate.now().plusYears(1))));

        assertThat(service.register(restoreReq(), "M1", "d").licenseKey()).isEqualTo("PAID-KEY");
        assertNothingCreated();
    }

    /** A perpetual licence has no expiry at all and must not be filtered out as unusable. */
    @Test
    void aPerpetualLicenceCountsAsUsable() {
        when(trials.findByMachineId("M1")).thenReturn(
                Optional.of(registration("c-4", "M1", LocalDate.now().minusMonths(6).atStartOfDay())));
        when(licenses.findAllByMachineIdAndClientCodeAndStatusOrderByIdDesc("M1", "c-4", "ACTIVE"))
                .thenReturn(List.of(licence("PERPETUAL", null)));

        assertThat(service.register(restoreReq(), "M1", "d").licenseKey()).isEqualTo("PERPETUAL");
    }

    /**
     * Handing back a key the desktop will reject as EXPIRED is not a restore, it is a loop -
     * so an ACTIVE row whose own expiry has passed is skipped, and a newer usable one wins.
     */
    @Test
    void anExpiredLicenceRowIsSkippedInFavourOfAUsableOne() {
        when(trials.findByMachineId("M1")).thenReturn(
                Optional.of(registration("c-4", "M1", LocalDate.now().minusMonths(6).atStartOfDay())));
        when(licenses.findAllByMachineIdAndClientCodeAndStatusOrderByIdDesc("M1", "c-4", "ACTIVE"))
                .thenReturn(List.of(licence("STALE", LocalDate.now().minusDays(2)),
                                    licence("GOOD", LocalDate.now().plusDays(30))));

        assertThat(service.register(restoreReq(), "M1", "d").licenseKey()).isEqualTo("GOOD");
    }

    @Test
    void anExpiredTrialWithNothingUsableIsStillRefused() {
        when(trials.findByMachineId("M1")).thenReturn(
                Optional.of(registration("c-4", "M1", LocalDate.now().minusMonths(6).atStartOfDay())));
        when(licenses.findAllByMachineIdAndClientCodeAndStatusOrderByIdDesc("M1", "c-4", "ACTIVE"))
                .thenReturn(List.of(licence("STALE", LocalDate.now().minusDays(2))));

        assertThatThrownBy(() -> service.register(restoreReq(), "M1", "d"))
                .isInstanceOf(TrialAlreadyUsedException.class);
        assertNothingCreated();
    }
}
