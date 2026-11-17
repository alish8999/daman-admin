package com.daman.admin.service;

import com.daman.admin.entity.ClientConfig;
import com.daman.admin.entity.License;
import com.daman.admin.repository.BillingRepository;
import com.daman.admin.repository.BuildLogRepository;
import com.daman.admin.repository.ClientConfigRepository;
import com.daman.admin.repository.LicenseRepository;
import com.daman.admin.repository.TrialRegistrationRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Resolution: a licence's effective feature set is its client's default with the licence's own
 * sparse override applied.
 *
 * <p>This is the function every mint path calls, so it is also where the live drift measured on
 * 2026-11-17 gets corrected — {@code viora} and {@code c-14} hold keys granting flags their
 * client records deny.
 */
class ClientConfigResolutionTest {

    ClientConfigRepository repository;
    ClientConfigService service;
    final ObjectMapper mapper = new ObjectMapper();

    @BeforeEach
    void setUp() {
        repository = mock(ClientConfigRepository.class);
        service = new ClientConfigService(repository, mapper,
                mock(LicenseRepository.class), mock(BillingRepository.class),
                mock(BuildLogRepository.class), mock(TrialRegistrationRepository.class));
    }

    private ClientConfig client(String code, String featuresJson) {
        ClientConfig c = new ClientConfig();
        c.setClientCode(code);
        c.setAppName("Shop " + code);
        c.setBaseCurrency("SYP");
        c.setPhone("0999");
        c.setAddress("Somewhere");
        c.setStoreType("grocery");
        c.setFeaturesJson(featuresJson);
        when(repository.findByClientCode(code)).thenReturn(Optional.of(c));
        return c;
    }

    private License licence(String code, String overrideJson) {
        License l = new License();
        l.setClientCode(code);
        l.setMachineId("m-" + code);
        l.setStatus("ACTIVE");
        l.setFeaturesOverrideJson(overrideJson);
        return l;
    }

    @Test
    void aLicenceWithNoOverrideResolvesToTheClientDefault() {
        client("c-1", "{\"accounting\":true,\"shifts\":false}");
        assertThat(service.licenseEntitlementsFor(licence("c-1", null)).features())
                .isEqualTo(service.licenseEntitlementsFor("c-1").features());
    }

    @Test
    void anOverrideTurnsAFlagOffThatTheClientHasOn() {
        client("c-1", "{\"accounting\":true}");
        assertThat(service.licenseEntitlementsFor(licence("c-1", "{\"accounting\":false}")).features())
                .containsEntry("accounting", false);
    }

    @Test
    void anOverrideTurnsAFlagOnThatTheClientHasOff() {
        client("c-1", "{\"deviceRepair\":false}");
        assertThat(service.licenseEntitlementsFor(licence("c-1", "{\"deviceRepair\":true}")).features())
                .containsEntry("deviceRepair", true);
    }

    /** The point of a SPARSE override: everything it does not mention still follows the business. */
    @Test
    void anOverrideLeavesEveryOtherFlagInherited() {
        client("c-1", "{\"accounting\":true,\"quotation\":true,\"shifts\":true}");
        var resolved = service.licenseEntitlementsFor(licence("c-1", "{\"accounting\":false}")).features();
        var dflt = service.licenseEntitlementsFor("c-1").features();
        assertThat(resolved).containsEntry("accounting", false);
        dflt.forEach((k, v) -> {
            if (!k.equals("accounting")) assertThat(resolved).containsEntry(k, v);
        });
    }

    @Test
    void anOverrideKeyThatIsNotAKnownFlagIsIgnored() {
        client("c-1", "{\"accounting\":true}");
        var resolved = service.licenseEntitlementsFor(licence("c-1", "{\"renamedFlag\":true}")).features();
        assertThat(resolved).doesNotContainKey("renamedFlag");
        assertThat(resolved).isEqualTo(service.licenseEntitlementsFor("c-1").features());
    }

    /** Every freshly created client has a null features_json; resolution must not blow up on it. */
    @Test
    void aClientWithNullFeaturesJsonResolvesToClientFeaturesJsonDefaults() {
        client("c-new", null);
        var features = service.licenseEntitlementsFor(licence("c-new", null)).features();
        assertThat(features).containsEntry("multiCurrency", true);   // the one flag defaulting true
        assertThat(features).containsEntry("accounting", false);
        assertThat(features).containsEntry("deviceRepair", false);
    }

    @Test
    void aClientWithABlankFeaturesJsonAlsoResolves() {
        client("c-blank", "   ");
        assertThat(service.licenseEntitlementsFor(licence("c-blank", null)).features())
                .containsEntry("multiCurrency", true);
    }

    /** The orphan contract is unchanged — the re-mint path is what has to survive it, not this. */
    @Test
    void anOrphanLicenceStillThrowsIllegalArgument() {
        when(repository.findByClientCode("gone")).thenReturn(Optional.empty());
        assertThatThrownBy(() -> service.licenseEntitlementsFor(licence("gone", null)))
                .isInstanceOf(IllegalArgumentException.class);
    }

    /** An override is about features only: currency, colours, credentials and profile stay the client's. */
    @Test
    void baseCurrencyAndProfileStillComeFromTheClient() {
        client("c-1", "{\"accounting\":true}");
        var ent = service.licenseEntitlementsFor(licence("c-1", "{\"accounting\":false}"));
        assertThat(ent.baseCurrency()).isEqualTo("SYP");
        assertThat(ent.profile().appName()).isEqualTo("Shop c-1");
        assertThat(ent.profile().phone()).isEqualTo("0999");
        assertThat(ent.profile().storeType()).isEqualTo("grocery");
    }

    @Test
    void aMalformedOverrideResolvesToTheClientDefault() {
        client("c-1", "{\"accounting\":true}");
        assertThat(service.licenseEntitlementsFor(licence("c-1", "{broken")).features())
                .isEqualTo(service.licenseEntitlementsFor("c-1").features());
    }
}
