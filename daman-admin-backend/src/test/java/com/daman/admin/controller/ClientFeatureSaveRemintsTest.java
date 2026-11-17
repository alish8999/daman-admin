package com.daman.admin.controller;

import com.daman.admin.dto.ClientConfigDto;
import com.daman.admin.dto.ClientConfigRequest;
import com.daman.admin.dto.FeaturesRequest;
import com.daman.admin.service.BuildService;
import com.daman.admin.service.ClientConfigService;
import com.daman.admin.service.DevRunService;
import com.daman.admin.service.LicenceRemintService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Saving a client's features must reach the shops, not just the database.
 *
 * <p>Before this, {@code update()} wrote {@code features_json} and stopped — and on a generic
 * build the signed key is the only thing that actually turns a feature on, so a sold add-on sat
 * in the admin database until someone happened to renew that licence. Measured live: five of the
 * eight drifting keys were exactly that.
 *
 * <p>Both write paths re-mint. The dedicated endpoint is what the new UI's confirmed "save
 * features" action calls; the existing profile PUT keeps working because today's portal sends
 * features inside it, and leaving that path mint-less would silently strand feature saves until
 * the UI phases land.
 *
 * <p><b>The re-mint is called from the controller, not from {@code ClientConfigService}</b> —
 * {@code LicenceRemintService} already depends on {@code ClientConfigService} to resolve
 * entitlements, so the reverse edge would be a circular bean dependency.
 */
class ClientFeatureSaveRemintsTest {

    ClientConfigService service;
    LicenceRemintService remint;
    ClientController controller;

    @BeforeEach
    void setUp() {
        service = mock(ClientConfigService.class);
        remint = mock(LicenceRemintService.class);
        controller = new ClientController(service, mock(BuildService.class),
                mock(DevRunService.class), remint);
        when(service.update(anyString(), any())).thenReturn(ClientConfigDto.builder().clientCode("c-1").build());
        when(service.updateFeatures(anyString(), any())).thenReturn(ClientConfigDto.builder().clientCode("c-1").build());
    }

    private void featuresGoFromTo(Map<String, Boolean> before, Map<String, Boolean> after) {
        when(service.clientFeatures("c-1")).thenReturn(before, after);
    }

    /**
     * <b>The profile save mints nothing, ever.</b>
     *
     * <p>It used to re-mint whenever the saved feature set differed, because the portal carried
     * features inside the profile payload and had no separate action. That is what made the new
     * confirmed "Save features" button a promise rather than a gate: the client page's features
     * grid writes into the same FormGroup the page's generic "Save changes" button submits, so
     * flipping a feature off and pressing the wrong button went down THIS path — no warning
     * naming the loss, no per-licence outcome report, and the flag stripped from every live key
     * with nothing on screen but "Client saved".
     *
     * <p>The gate is structural now: {@code ClientConfigService.update()} ignores the features
     * in the request entirely (see {@code ClientConfigServiceTest.update_ignoresFeaturesEntirely}),
     * so there is nothing left for this endpoint to detect or mint.
     */
    @Test
    void theProfileSaveNeverReminits() {
        featuresGoFromTo(Map.of("accounting", false), Map.of("accounting", true));

        ClientConfigRequest request = new ClientConfigRequest();
        request.setAppName("Renamed Shop");
        FeaturesRequest sneaky = new FeaturesRequest();
        sneaky.setAccounting(true);
        request.setFeatures(sneaky);

        controller.update("c-1", request);

        verify(remint, never()).remintForClient(anyString(), any());
    }

    @Test
    void aProfileOnlyUpdateMintsNothing() {
        Map<String, Boolean> same = Map.of("accounting", true, "shifts", false);
        featuresGoFromTo(same, same);

        ClientConfigRequest request = new ClientConfigRequest();
        request.setAppName("Renamed Shop");
        controller.update("c-1", request);

        verify(remint, never()).remintForClient(anyString(), any());
    }
    @Test
    void theDedicatedFeaturesEndpointSavesAndRemints() {
        when(remint.remintForClient(anyString(), any())).thenReturn(List.of(
                new LicenceRemintService.RemintOutcome(1L, "c-1", "m-1", true, null)));
        featuresGoFromTo(Map.of("accounting", false), Map.of("accounting", true));
        FeaturesRequest patch = new FeaturesRequest();
        patch.setAccounting(true);

        var response = controller.updateFeatures("c-1", patch);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        verify(service).updateFeatures("c-1", patch);
        verify(remint).remintForClient("c-1", java.util.Set.of("accounting"));
    }

    @Test
    void theDedicatedEndpointReportsWhatHappenedToEachLicence() {
        when(remint.remintForClient(anyString(), any())).thenReturn(List.of(
                new LicenceRemintService.RemintOutcome(1L, "c-1", "m-1", true, null),
                new LicenceRemintService.RemintOutcome(2L, "c-1", "m-2", false, "expired")));
        featuresGoFromTo(Map.of("accounting", false), Map.of("accounting", true));
        FeaturesRequest patch = new FeaturesRequest();
        patch.setAccounting(true);

        var body = (ClientController.FeatureSaveResponse) controller.updateFeatures("c-1", patch).getBody();

        assertThat(body.remintOutcomes()).hasSize(2);
        assertThat(body.remintOutcomes().get(1).skippedReason()).isEqualTo("expired");
    }

    /** A licence that cannot be re-minted must not undo a correct save. */
    @Test
    void remintFailureDoesNotLoseTheSavedFeatures() {
        when(remint.remintForClient(anyString(), any())).thenThrow(new RuntimeException("signing unavailable"));
        featuresGoFromTo(Map.of("accounting", false), Map.of("accounting", true));
        FeaturesRequest patch = new FeaturesRequest();
        patch.setAccounting(true);

        var response = controller.updateFeatures("c-1", patch);

        verify(service).updateFeatures("c-1", patch);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        var body = (ClientController.FeatureSaveResponse) response.getBody();
        assertThat(body.remintError()).contains("signing unavailable");
    }
}
