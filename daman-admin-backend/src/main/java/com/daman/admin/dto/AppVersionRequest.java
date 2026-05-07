package com.daman.admin.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

import java.time.LocalDate;

@Data
public class AppVersionRequest {
    @NotBlank
    private String versionNumber;

    @NotNull
    private LocalDate releaseDate;

    private String changelogText;
}
