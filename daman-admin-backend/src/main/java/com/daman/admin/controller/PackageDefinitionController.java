package com.daman.admin.controller;

import com.daman.admin.dto.PackageDefinitionsDto;
import com.daman.admin.service.PackageDefinitionService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/packages")
@RequiredArgsConstructor
public class PackageDefinitionController {

    private final PackageDefinitionService service;

    @GetMapping
    public PackageDefinitionsDto get() {
        return service.getDefinitions();
    }

    @PutMapping
    public PackageDefinitionsDto save(@RequestBody PackageDefinitionsDto dto) {
        return service.saveDefinitions(dto);
    }
}
