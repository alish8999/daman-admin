package com.daman.admin.controller;

import com.daman.admin.dto.TrialRegistrationDto;
import com.daman.admin.repository.TrialRegistrationRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDateTime;
import java.util.List;

/**
 * ADMIN-ONLY. A list of prospects' names and phone numbers. Deliberately NOT in
 * AuthFilter.PUBLIC_PATHS (pinned by AuthFilterPublicPathsTest), and deliberately a separate
 * class from the anonymous {@link TrialController}.
 */
@RestController
@RequestMapping("/api/trials")
@RequiredArgsConstructor
public class TrialListController {

    private final TrialRegistrationRepository repository;

    @GetMapping
    public List<TrialRegistrationDto> list() {
        LocalDateTime now = LocalDateTime.now();
        return repository.findAllByOrderByRegisteredAtDesc().stream()
                .map(t -> TrialRegistrationDto.of(t, now))
                .toList();
    }
}
