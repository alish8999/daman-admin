package com.daman.admin.service;

import org.springframework.stereotype.Service;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/**
 * Packages a {@link LicenseReissueService.ReissueResult} into a downloadable zip:
 * one {@code <clientCode>.dat} (the new v2 key) plus {@code <clientCode>.v1-backup.dat}
 * (the pre-migration key, for rollback) per re-issued licence, a {@code checklist.csv}
 * the developer works through client-by-client, and a {@code README.txt}.
 */
@Service
public class LicenseBundleZipper {

    public byte[] zip(LicenseReissueService.ReissueResult result) {
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        try (ZipOutputStream zos = new ZipOutputStream(bos)) {
            for (LicenseReissueService.ReissuedLicence r : result.reissued()) {
                putEntry(zos, r.datFileName(), r.newKey());
                putEntry(zos, backupName(r.datFileName()), r.previousKey());
            }
            putEntry(zos, "checklist.csv", buildChecklist(result));
            putEntry(zos, "README.txt", README);
        } catch (IOException e) {
            throw new RuntimeException("Failed to build licence bundle", e);
        }
        return bos.toByteArray();
    }

    private static String backupName(String datFileName) {
        return datFileName.endsWith(".dat")
                ? datFileName.substring(0, datFileName.length() - 4) + ".v1-backup.dat"
                : datFileName + ".v1-backup.dat";
    }

    private static void putEntry(ZipOutputStream zos, String name, String content) throws IOException {
        zos.putNextEntry(new ZipEntry(name));
        zos.write((content == null ? "" : content).getBytes(StandardCharsets.UTF_8));
        zos.closeEntry();
    }

    private static String buildChecklist(LicenseReissueService.ReissueResult result) {
        StringBuilder sb = new StringBuilder(
                "clientCode,clientName,machineId,fromVersion,datFile,SENT,REACTIVATED,CONFIRMED\n");
        for (LicenseReissueService.ReissuedLicence r : result.reissued()) {
            sb.append(csv(r.clientCode())).append(',')
              .append(csv(r.clientName())).append(',')
              .append(csv(r.machineId())).append(',')
              .append(r.fromVersion()).append(',')
              .append(csv(r.datFileName())).append(",,,\n");
        }
        for (LicenseReissueService.ReissuePreviewRow s : result.skipped()) {
            sb.append(csv(s.clientCode())).append(',')
              .append(csv(s.clientName())).append(',')
              .append(csv(s.machineId())).append(',')
              .append(s.currentVersion()).append(',')
              .append("SKIPPED-NO-CLIENTCONFIG,,,\n");
        }
        return sb.toString();
    }

    private static String csv(String v) {
        if (v == null) return "";
        if (v.contains(",") || v.contains("\"") || v.contains("\n")) {
            return "\"" + v.replace("\"", "\"\"") + "\"";
        }
        return v;
    }

    private static final String README = String.join("\n",
            "Daman - Stage-1 v2 licence bundle",
            "",
            "Each <clientCode>.dat is a v2 licence for that client's SAME machine and",
            "clientCode, carrying their baseCurrency + feature set inside the signed",
            "payload. Send each client THEIR .dat and have them re-activate once from the",
            "licence screen.",
            "",
            "Nothing about their running app changes at this stage: on their current",
            "per-client build a v2 licence is accepted exactly like a v1 licence.",
            "",
            "Rollback: the matching <clientCode>.v1-backup.dat is that client's",
            "pre-migration licence - it still activates if anything looks wrong.",
            "",
            "A client is NOT eligible for the generic build (Stage 2) until you have",
            "CONFIRMED their re-activation - a daily backup showing the v2 key, or a",
            "support check. Track that in checklist.csv.",
            "");
}
