package com.daman.admin.controller;

import com.daman.admin.dto.BillingDto;
import com.daman.admin.dto.BillingRequest;
import com.daman.admin.entity.Billing;
import com.daman.admin.repository.BillingRepository;
import com.daman.admin.repository.LicenseRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDate;
import java.util.List;
import java.util.stream.Collectors;

@RestController
@RequestMapping("/api/billings")
@RequiredArgsConstructor
public class BillingController {

    private final BillingRepository billingRepository;
    private final LicenseRepository licenseRepository;

    @GetMapping
    public List<BillingDto> getAll() {
        return billingRepository.findAllByOrderByCreatedAtDesc()
                .stream().map(this::toDto).collect(Collectors.toList());
    }

    @GetMapping("/client/{clientCode}")
    public List<BillingDto> getByClient(@PathVariable String clientCode) {
        return billingRepository.findByClientCodeOrderByCreatedAtDesc(clientCode)
                .stream().map(this::toDto).collect(Collectors.toList());
    }

    @PostMapping("/client/{clientCode}")
    public ResponseEntity<?> create(@PathVariable String clientCode, @RequestBody BillingRequest req) {
        if (!licenseBelongsToClient(req.getLicenseId(), clientCode)) {
            return ResponseEntity.badRequest().body("licenseId does not belong to this client");
        }
        Billing b = new Billing();
        b.setClientCode(clientCode);
        apply(b, req);
        return ResponseEntity.ok(toDto(billingRepository.save(b)));
    }

    @PutMapping("/{id}")
    public ResponseEntity<?> update(@PathVariable Long id, @RequestBody BillingRequest req) {
        Billing b = billingRepository.findById(id).orElse(null);
        if (b == null) return ResponseEntity.notFound().build();
        if (!licenseBelongsToClient(req.getLicenseId(), b.getClientCode())) {
            return ResponseEntity.badRequest().body("licenseId does not belong to this client");
        }
        apply(b, req);
        return ResponseEntity.ok(toDto(billingRepository.save(b)));
    }

    private boolean licenseBelongsToClient(Long licenseId, String clientCode) {
        if (licenseId == null) return true;
        return licenseRepository.findById(licenseId)
                .map(l -> clientCode.equals(l.getClientCode()))
                .orElse(false);
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Void> delete(@PathVariable Long id) {
        if (!billingRepository.existsById(id)) return ResponseEntity.notFound().build();
        billingRepository.deleteById(id);
        return ResponseEntity.noContent().build();
    }

    private void apply(Billing b, BillingRequest req) {
        b.setLicenseId(req.getLicenseId());
        b.setAmount(req.getAmount());
        b.setPaymentMethod(req.getPaymentMethod());
        b.setPaymentStatus(req.getPaymentStatus());
        b.setInvoiceRef(req.getInvoiceRef());
        b.setPaymentDate(parseDate(req.getPaymentDate()));
        b.setSupportStartDate(parseDate(req.getSupportStartDate()));
        b.setSupportEndDate(parseDate(req.getSupportEndDate()));
        b.setNotes(req.getNotes());
    }

    private LocalDate parseDate(String s) {
        return (s != null && !s.isBlank()) ? LocalDate.parse(s) : null;
    }

    private BillingDto toDto(Billing b) {
        return BillingDto.builder()
                .id(b.getId())
                .clientCode(b.getClientCode())
                .licenseId(b.getLicenseId())
                .amount(b.getAmount())
                .paymentMethod(b.getPaymentMethod())
                .paymentStatus(b.getPaymentStatus())
                .invoiceRef(b.getInvoiceRef())
                .paymentDate(b.getPaymentDate())
                .supportStartDate(b.getSupportStartDate())
                .supportEndDate(b.getSupportEndDate())
                .notes(b.getNotes())
                .createdAt(b.getCreatedAt())
                .build();
    }
}
