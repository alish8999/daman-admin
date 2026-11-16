package com.daman.admin.service;

import com.daman.admin.dto.BuildStatusDto;
import com.daman.admin.repository.AppVersionRepository;
import com.daman.admin.repository.BuildLogRepository;
import com.daman.admin.repository.ClientConfigRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.test.util.ReflectionTestUtils;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;

/**
 * The Android half of the generic build: the phone till's APK.
 *
 * Covers the parts that cannot be checked by looking at a finished APK - the
 * number Android actually compares updates on, and the refusal to build a
 * per-client one.
 */
class BuildServiceAndroidBranchTest {

    @TempDir
    Path tempDir;

    private BuildService buildService;

    @BeforeEach
    void setUp() {
        buildService = new BuildService(
                mock(ClientConfigRepository.class),
                mock(ClientConfigService.class),
                mock(BuildLogRepository.class),
                mock(LicenseKeyService.class),
                mock(AppVersionRepository.class));
        ReflectionTestUtils.setField(buildService, "workspacePath", tempDir.toString());
    }

    private Object invokePrivate(String name, Class<?>[] paramTypes, Object... args) throws Exception {
        Method m = BuildService.class.getDeclaredMethod(name, paramTypes);
        m.setAccessible(true);
        try {
            return m.invoke(buildService, args);
        } catch (InvocationTargetException e) {
            throw (Exception) e.getCause();
        }
    }

    /** addLog writes into the status it is given; production always has one. */
    private BuildStatusDto newStatus() {
        return BuildStatusDto.builder()
                .clientCode("generic")
                .logs(java.util.Collections.synchronizedList(new java.util.ArrayList<>()))
                .build();
    }

    private int versionCode(String semver) throws Exception {
        return (int) invokePrivate("androidVersionCode", new Class<?>[]{String.class}, semver);
    }

    private void stampVersion(Path frontendRoot, String semver) throws Exception {
        invokePrivate("writeAndroidVersion",
                new Class<?>[]{Path.class, String.class, BuildStatusDto.class},
                frontendRoot, semver, newStatus());
    }

    // -- versionCode ---------------------------------------------------------
    // Android decides what counts as an update from versionCode alone;
    // versionName is a label it never compares. Get this wrong and a phone
    // refuses a newer APK as a downgrade, with no useful message.

    @Test
    void versionCode_packsSemverIntoOneAscendingInteger() throws Exception {
        assertThat(versionCode("1.5.1")).isEqualTo(10501);
        assertThat(versionCode("2.0.0")).isEqualTo(20000);
        assertThat(versionCode("1.5.10")).isEqualTo(10510);
        assertThat(versionCode("0.0.1")).isEqualTo(1);
    }

    @Test
    void versionCode_risesWithEveryRelease() throws Exception {
        assertThat(versionCode("1.4.9")).isLessThan(versionCode("1.5.0"));
        assertThat(versionCode("1.5.0")).isLessThan(versionCode("1.5.1"));
        assertThat(versionCode("1.9.99")).isLessThan(versionCode("2.0.0"));
    }

    @Test
    void versionCode_refusesANumberThatWouldCollideWithTheNextRelease() throws Exception {
        // 1.5.100 packs to 10600, which is 1.6.0 - a silent collision that makes
        // a real update look to Android like the build already installed.
        assertThatThrownBy(() -> versionCode("1.5.100")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> versionCode("1.100.0")).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void versionCode_refusesAVersionItCannotRead() throws Exception {
        assertThatThrownBy(() -> versionCode("")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> versionCode("1.5")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> versionCode("latest")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> versionCode(null)).isInstanceOf(IllegalArgumentException.class);
    }

    // -- stamping build.gradle -----------------------------------------------

    private Path gradleFile(String versionCodeLine, String versionNameLine) throws Exception {
        Path app = tempDir.resolve("daman-frontend").resolve("android").resolve("app");
        Files.createDirectories(app);
        Path gradle = app.resolve("build.gradle");
        Files.writeString(gradle, String.join("\n",
                "android {",
                "    defaultConfig {",
                "        applicationId \"com.daman.mobilepos\"",
                "        " + versionCodeLine,
                "        " + versionNameLine,
                "    }",
                "}"), StandardCharsets.UTF_8);
        return gradle;
    }

    @Test
    void writeAndroidVersion_stampsBothFieldsFromTheAppVersionsRow() throws Exception {
        Path gradle = gradleFile("versionCode 1", "versionName \"1.0\"");
        stampVersion(tempDir.resolve("daman-frontend"), "1.5.1");

        String text = Files.readString(gradle, StandardCharsets.UTF_8);
        assertThat(text).contains("versionCode 10501");
        assertThat(text).contains("versionName \"1.5.1\"");
        assertThat(text).contains("applicationId \"com.daman.mobilepos\"");
    }

    @Test
    void writeAndroidVersion_isSafeToRunTwice() throws Exception {
        gradleFile("versionCode 1", "versionName \"1.0\"");
        Path frontend = tempDir.resolve("daman-frontend");
        Path gradle = frontend.resolve("android").resolve("app").resolve("build.gradle");

        stampVersion(frontend, "1.5.1");
        String once = Files.readString(gradle, StandardCharsets.UTF_8);
        stampVersion(frontend, "1.5.1");
        String twice = Files.readString(gradle, StandardCharsets.UTF_8);

        assertThat(twice).isEqualTo(once);
    }

    @Test
    void writeAndroidVersion_failsLoudlyRatherThanShippingTheOldNumber() throws Exception {
        // A build.gradle whose fields were renamed or removed must stop the build.
        // Carrying on would produce an APK stamped 1.0 that every phone already on
        // 1.5.1 refuses as a downgrade.
        Path app = tempDir.resolve("daman-frontend").resolve("android").resolve("app");
        Files.createDirectories(app);
        Files.writeString(app.resolve("build.gradle"), "android { defaultConfig { } }", StandardCharsets.UTF_8);

        assertThatThrownBy(() -> stampVersion(tempDir.resolve("daman-frontend"), "1.5.1"))
                .hasMessageContaining("versionCode");
    }

    // -- the gradle command --------------------------------------------------

    @Test
    void androidGradleCommand_buildsAReleaseApk() throws Exception {
        String[] cmd = (String[]) invokePrivate("androidGradleCommand", new Class<?>[]{});
        assertThat(String.join(" ", cmd)).contains("assembleRelease");
    }

    // -- naming the artifact -------------------------------------------------

    private String relocate(String builtFileName) throws Exception {
        Path src = tempDir.resolve(builtFileName);
        Files.writeString(src, "apk", StandardCharsets.UTF_8);
        return (String) invokePrivate("relocateArtifact",
                new Class<?>[]{String.class, String.class, String.class, String.class,
                        Path.class, String.class, BuildStatusDto.class},
                src.toString(), "generic", "Daman", "1.5.1", tempDir, "android", newStatus());
    }

    @Test
    void relocateArtifact_namesASignedApkAfterTheRelease() throws Exception {
        String out = relocate("app-release.apk");
        assertThat(Path.of(out).getFileName().toString()).isEqualTo("Daman-Accounting_1.5.1_generic.apk");
    }

    @Test
    void relocateArtifact_keepsTheUnsignedMarkerInTheName() throws Exception {
        // Gradle says "unsigned" in its own filename and the rename used to throw that
        // away, leaving a file in clients-build/ that looks exactly like a shippable
        // one. The marker is the only thing distinguishing them at a glance, and it is
        // what deploy-mobile-release.ps1 checks before it even reaches apksigner.
        String out = relocate("app-release-unsigned.apk");
        assertThat(Path.of(out).getFileName().toString()).isEqualTo("Daman-Accounting_1.5.1_generic-unsigned.apk");
    }

    // -- no per-client APK ---------------------------------------------------

    @Test
    void startBuild_refusesAnAndroidBuildForOneClient() {
        // The APK carries no client identity at all - the phone till's
        // entitlement comes from the licence of the PC it connects to. A
        // per-client Android build would be the same bytes under a name that
        // claims otherwise.
        assertThatThrownBy(() -> buildService.startBuild("viora", "android", "1.5.1"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("generic");
    }
}
