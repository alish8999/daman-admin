package com.daman.admin.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

import java.time.LocalDate;
import java.util.List;

@Data
public class AppVersionRequest {
    @NotBlank
    private String versionNumber;

    @NotNull
    private LocalDate releaseDate;

    private String changelogText;

    /**
     * Ids of the changelog-library bullets {@code changelogText} was composed
     * from. Saving the version marks each of them deployed, which is what
     * keeps the "new since last release" filter in the pickers honest.
     *
     * <p>Optional — a caller that doesn't send it (a hand-written API call, an
     * older client) simply marks nothing. Deliberately the ids and not a text
     * match against {@code changelogText}: the changelog is editable by hand,
     * and matching would quietly stop flagging anything the moment someone
     * reworded a line.
     */
    private List<String> changelogEntryIds;
}
