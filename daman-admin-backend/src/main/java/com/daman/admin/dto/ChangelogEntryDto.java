package com.daman.admin.dto;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * One reusable bullet in the changelog library file — see ChangelogLibraryService.
 * Carries both languages so the shipped app can show release notes in
 * whichever language the client has selected, not just whatever the admin
 * happened to type first.
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class ChangelogEntryDto {
    private String id;
    private String en;
    private String ar;

    /**
     * Whether this bullet already shipped — see ChangelogEntry.deployed.
     *
     * <p>Read-only as far as the pickers are concerned: a PUT that omits it
     * (or sends null) leaves the stored value alone rather than clearing it.
     * Both pickers send the WHOLE library on every save, so without that rule
     * any consumer that hasn't been taught about this field would wipe every
     * flag the moment someone edited one bullet.
     */
    private Boolean deployed;
}
