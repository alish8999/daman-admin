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
}
