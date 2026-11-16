package com.daman.admin.service;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.test.util.ReflectionTestUtils;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Publishing the phone till's APK to the public download page.
 *
 * Everything here is about refusing to publish, because that is the half with
 * consequences: the deploy pushes a public website, and a bad artifact there is
 * a download link that fails on every phone that tries it.
 */
class MobileDeployServiceTest {

    @TempDir
    Path workspace;

    private MobileDeployService service;

    @BeforeEach
    void setUp() {
        service = new MobileDeployService();
        ReflectionTestUtils.setField(service, "workspacePath", workspace.toString());
    }

    private Path buildDir() throws Exception {
        Path dir = workspace.resolve("clients-build").resolve("generic");
        Files.createDirectories(dir);
        return dir;
    }

    private void putApk(String name) throws Exception {
        Files.writeString(buildDir().resolve(name), "apk", StandardCharsets.UTF_8);
    }

    private void putScript() throws Exception {
        Path admin = workspace.resolve("daman-admin");
        Files.createDirectories(admin);
        Files.writeString(admin.resolve("deploy-mobile-release.ps1"), "# stub", StandardCharsets.UTF_8);
    }

    @Test
    void preflight_acceptsASignedApkForThatVersion() throws Exception {
        putScript();
        putApk("Daman_1.5.1_generic.apk");

        Path apk = service.preflight("1.5.1");

        assertThat(apk.getFileName().toString()).isEqualTo("Daman_1.5.1_generic.apk");
    }

    @Test
    void preflight_refusesWhenOnlyTheUnsignedBuildExists() throws Exception {
        putScript();
        putApk("Daman_1.5.1_generic-unsigned.apk");

        // Gradle produces this whenever the signing properties are absent, and it
        // installs on no phone. Publishing it is a download link that fails for
        // everyone who clicks it, which reads as a broken app rather than a
        // missing keystore.
        assertThatThrownBy(() -> service.preflight("1.5.1"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("unsigned")
                .hasMessageContaining("gradle.properties");
    }

    @Test
    void preflight_refusesAVersionThatWasNeverBuilt() throws Exception {
        putScript();
        putApk("Daman_1.5.0_generic.apk");

        assertThatThrownBy(() -> service.preflight("1.5.1"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("1.5.1");
    }

    @Test
    void preflight_refusesWhenTheDeployScriptIsMissing() throws Exception {
        putApk("Daman_1.5.1_generic.apk");

        assertThatThrownBy(() -> service.preflight("1.5.1"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("deploy-mobile-release.ps1");
    }

    @Test
    void preflight_refusesAVersionThatIsNotAVersion() throws Exception {
        putScript();
        putApk("Daman_1.5.1_generic.apk");

        // The version is pasted into a filename and then into a command line.
        // Anything but digits and dots is refused rather than quoted around.
        for (String bad : new String[]{"", "  ", "1.5.1; rm -rf /", "../../etc", "latest"}) {
            assertThatThrownBy(() -> service.preflight(bad))
                    .as("version %s", bad)
                    .isInstanceOf(IllegalArgumentException.class);
        }
    }

    @Test
    void deployCommand_runsTheScriptWithoutTheUsersProfileOrExecutionPolicy() throws Exception {
        putScript();
        String[] cmd = service.deployCommand("1.5.1");
        String joined = String.join(" ", cmd);

        assertThat(joined).contains("powershell");
        assertThat(joined).contains("-NoProfile");
        assertThat(joined).contains("-ExecutionPolicy Bypass");
        assertThat(joined).contains("deploy-mobile-release.ps1");
        assertThat(joined).contains("-Version 1.5.1");
    }

    @Test
    void status_startsIdleAndIsNotADeployInProgress() {
        assertThat(service.getStatus().getStatus()).isEqualTo("IDLE");
    }
}
