package com.daman.admin.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * One reusable changelog bullet, in both languages, backing the "Select
 * Changelog" picker (Versions page + Clients page's Confirm Build modal).
 * `id` is an assigned string (a slug for the seeded entries, "entry-<ts36>"
 * for ones added later from the picker), not a generated one — this is the
 * exact same external contract the previous file-based storage used, so
 * neither frontend picker needed any change when this moved off a JSON file.
 *
 * Replaces changelog-library.json (a plain file, checked in under
 * daman-admin-frontend) — that only worked when this backend ran on the same
 * machine as that repo checkout (the daman.workspace convention), which is
 * true for local dev but not for the hosted admin backend. A real table
 * works the same everywhere this backend runs, and avoids the previous
 * whole-file-overwrite-on-every-edit race. See ChangelogEntrySeeder for the
 * one-time migration of the file's original content.
 */
@Getter
@Setter
@Entity
@Table(name = "changelog_entries")
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class ChangelogEntry {

    @Id
    private String id;

    @Column(columnDefinition = "TEXT", nullable = false)
    private String en;

    @Column(columnDefinition = "TEXT", nullable = false)
    private String ar;
}
