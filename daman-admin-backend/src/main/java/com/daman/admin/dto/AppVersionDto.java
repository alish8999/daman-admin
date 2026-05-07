package com.daman.admin.dto;

import lombok.Builder;
import lombok.Data;

import java.time.LocalDate;
import java.time.LocalDateTime;

@Data
@Builder
public class AppVersionDto {
    private Long id;
    private String versionNumber;
    private LocalDate releaseDate;
    private String changelogText;
    private LocalDateTime createdAt;
}
