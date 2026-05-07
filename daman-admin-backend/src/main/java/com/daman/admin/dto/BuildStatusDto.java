package com.daman.admin.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

@Data
@Builder
@AllArgsConstructor
@NoArgsConstructor
public class BuildStatusDto {

    private String clientCode;
    private String platform;
    private String status;
    private LocalDateTime startedAt;
    private LocalDateTime finishedAt;
    private String artifactPath;
    private String artifactName;

    @Builder.Default
    private List<String> logs = Collections.synchronizedList(new ArrayList<>());
}
