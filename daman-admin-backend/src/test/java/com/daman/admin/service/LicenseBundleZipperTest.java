package com.daman.admin.service;

import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

import static org.assertj.core.api.Assertions.assertThat;

class LicenseBundleZipperTest {

    private final LicenseBundleZipper zipper = new LicenseBundleZipper();

    private Map<String, String> unzip(byte[] bytes) throws Exception {
        Map<String, String> out = new HashMap<>();
        try (ZipInputStream zis = new ZipInputStream(new ByteArrayInputStream(bytes))) {
            ZipEntry e;
            while ((e = zis.getNextEntry()) != null) {
                ByteArrayOutputStream bos = new ByteArrayOutputStream();
                zis.transferTo(bos);
                out.put(e.getName(), bos.toString(StandardCharsets.UTF_8));
            }
        }
        return out;
    }

    @Test
    void zip_writesDatPlusBackupPlusChecklistAndReadme() throws Exception {
        var result = new LicenseReissueService.ReissueResult(
                List.of(new LicenseReissueService.ReissuedLicence(
                        "acme", "Acme POS", "M-AAAA", 1, "acme.dat", "NEWKEY.v2", "OLDKEY.v1")),
                List.of());

        Map<String, String> entries = unzip(zipper.zip(result));

        assertThat(entries).containsKeys("acme.dat", "acme.v1-backup.dat", "checklist.csv", "README.txt");
        assertThat(entries.get("acme.dat")).isEqualTo("NEWKEY.v2");
        assertThat(entries.get("acme.v1-backup.dat")).isEqualTo("OLDKEY.v1");
        assertThat(entries.get("checklist.csv"))
                .startsWith("clientCode,clientName,machineId,fromVersion,datFile,SENT,REACTIVATED,CONFIRMED")
                .contains("acme,Acme POS,M-AAAA,1,acme.dat,,,");
        assertThat(entries.get("README.txt")).contains("v1-backup").contains("re-activate");
    }

    @Test
    void zip_skippedRowsAppearInChecklistNotAsDatFiles() throws Exception {
        var result = new LicenseReissueService.ReissueResult(
                List.of(),
                List.of(new LicenseReissueService.ReissuePreviewRow(
                        "ghost", "Ghost", "M-GHOST", 1, "2027-01-01", false)));

        Map<String, String> entries = unzip(zipper.zip(result));

        assertThat(entries).containsOnlyKeys("checklist.csv", "README.txt");
        assertThat(entries.get("checklist.csv")).contains("ghost").contains("SKIPPED-NO-CLIENTCONFIG");
    }

    @Test
    void zip_quotesCsvFieldsContainingCommas() throws Exception {
        var result = new LicenseReissueService.ReissueResult(
                List.of(new LicenseReissueService.ReissuedLicence(
                        "acme", "Acme, Inc.", "M-AAAA", 2, "acme.dat", "K", "P")),
                List.of());

        String csv = unzip(zipper.zip(result)).get("checklist.csv");

        assertThat(csv).contains("\"Acme, Inc.\"");
    }
}
