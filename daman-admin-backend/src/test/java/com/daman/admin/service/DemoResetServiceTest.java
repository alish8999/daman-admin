package com.daman.admin.service;

import com.daman.admin.entity.HostedInstance;
import com.daman.admin.repository.HostedInstanceRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.test.util.ReflectionTestUtils;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

/**
 * Reset destroys a store's database. The refusals below are the point of this class: the happy
 * path merely copies a file, while a guard that fails open would wipe a paying customer.
 */
class DemoResetServiceTest {

    private HostedInstanceRepository repository;
    private ServerOps ops;
    private DemoResetService service;
    private Path seeds;

    @BeforeEach
    void setUp(@TempDir Path dir) throws Exception {
        repository = mock(HostedInstanceRepository.class);
        ops = mock(ServerOps.class);
        when(ops.health(anyInt())).thenReturn(true);
        service = new DemoResetService(repository, ops);

        seeds = dir.resolve("seeds");
        Files.createDirectories(seeds);
        Files.writeString(seeds.resolve("mart.sqlite"), "not-a-real-db");
        ReflectionTestUtils.setField(service, "seedDir", seeds.toString());
    }

    private HostedInstance row(String code, boolean demo, String status) {
        HostedInstance i = new HostedInstance();
        i.setClientCode(code);
        i.setServiceName("daman-store-" + code);
        i.setDataDir("/root/stores/" + code);
        i.setPort(8099);
        i.setStatus(status);
        i.setDemo(demo);
        i.setDemoFlavour(demo ? "mart" : null);
        when(repository.findByClientCode(code)).thenReturn(Optional.of(i));
        return i;
    }

    @Test
    void refusesAStoreThatIsNotADemo() {
        row("viora", false, "ACTIVE");
        assertThatThrownBy(() -> service.reset("viora"))
                .isInstanceOf(DemoResetService.RefusedException.class)
                .hasMessageContaining("not a demo slot");
    }

    @Test
    void refusingANonDemoStoreTouchesNothingAtAll() {
        // The refusal must come BEFORE anything is stopped. A guard that stops the unit and then
        // changes its mind has already taken a customer's shop offline.
        row("viora", false, "ACTIVE");
        assertThatThrownBy(() -> service.reset("viora")).isInstanceOf(DemoResetService.RefusedException.class);
        verify(ops, never()).stopAndDisable(anyString());
        verify(ops, never()).enableAndStart(anyString());
        verifyNoMoreInteractions(ops);
    }

    @Test
    void refusesAnUnknownStore() {
        when(repository.findByClientCode("ghost")).thenReturn(Optional.empty());
        assertThatThrownBy(() -> service.reset("ghost"))
                .isInstanceOf(DemoResetService.RefusedException.class)
                .hasMessageContaining("No hosted store");
        verifyNoInteractions(ops);
    }

    @Test
    void refusesWhileStillProvisioning() {
        row("demo1", true, "PROVISIONING");
        assertThatThrownBy(() -> service.reset("demo1"))
                .isInstanceOf(DemoResetService.RefusedException.class)
                .hasMessageContaining("provisioning");
        verify(ops, never()).stopAndDisable(anyString());
    }

    @Test
    void refusesWhenTheSeedForItsFlavourIsMissing() {
        HostedInstance i = row("demo2", true, "ACTIVE");
        i.setDemoFlavour("mobile"); // only mart.sqlite exists in the temp seed dir
        assertThatThrownBy(() -> service.reset("demo2"))
                .isInstanceOf(DemoResetService.RefusedException.class)
                .hasMessageContaining("No seed for flavour");
        verify(ops, never()).stopAndDisable(anyString());
    }

    @Test
    void refusesAFlavourThatIsNotWhitelisted() {
        // The flavour becomes part of a filesystem path, so anything outside the whitelist is
        // refused rather than cleaned up.
        assertThatThrownBy(() -> service.seedFor("../../etc/passwd"))
                .isInstanceOf(DemoResetService.RefusedException.class);
        assertThatThrownBy(() -> service.seedFor(null))
                .isInstanceOf(DemoResetService.RefusedException.class);
        assertThatThrownBy(() -> service.seedFor("mart; rm -rf /"))
                .isInstanceOf(DemoResetService.RefusedException.class);
    }

    @Test
    void seedPathIsBuiltFromTheWhitelistedFlavour() {
        assertThat(service.seedFor("mart")).isEqualTo(seeds.resolve("mart.sqlite"));
        assertThat(service.seedFor("  MART  ")).isEqualTo(seeds.resolve("mart.sqlite"));
    }

    @Test
    void databasePathMatchesWhereAHostedStoreActuallyKeepsIt() {
        HostedInstance i = row("demo1", true, "ACTIVE");
        assertThat(service.databaseOf(i))
                .isEqualTo(Path.of("/root/stores/demo1", ".daman", "demo1", "daman_db.sqlite"));
    }

    @Test
    void aNewHostedInstanceIsNotADemoUntilSaidOtherwise() {
        // Defaulting to false is what stops a row created before this column existed, or by any
        // path that forgets to set it, from being resettable.
        assertThat(new HostedInstance().isDemo()).isFalse();
    }
}
