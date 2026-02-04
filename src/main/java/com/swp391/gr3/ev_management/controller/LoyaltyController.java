package com.swp391.gr3.ev_management.controller;

import com.swp391.gr3.ev_management.entity.Invoice;
import com.swp391.gr3.ev_management.entity.UserVehicle;
import com.swp391.gr3.ev_management.exception.ErrorException;
import com.swp391.gr3.ev_management.repository.VehicleLoyaltyRepository;
import com.swp391.gr3.ev_management.service.InvoiceService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

@RestController
@RequestMapping("/api/loyalty")
@RequiredArgsConstructor
public class LoyaltyController {

    private final VehicleLoyaltyRepository loyaltyRepository;
    private final InvoiceService invoiceService;

    @GetMapping("/available-by-invoice")
    public Map<String, Object> availableByInvoice(@RequestParam Long invoiceId) {
        Invoice invoice = invoiceService.findById(invoiceId)
                .orElseThrow(() -> new ErrorException("Invoice not found"));

        UserVehicle vehicle = invoice.getSession().getBooking().getVehicle();
        Long vehicleId = vehicle.getVehicleId();

        int points = loyaltyRepository.findById(vehicleId)
                .map(v -> v.getPointsBalance())
                .orElse(0);

        return Map.of(
                "vehicleId", vehicleId,
                "pointsAvailable", points
        );
    }

    @GetMapping("/available-by-vehicle")
    public Map<String, Object> availableByVehicle(@RequestParam Long vehicleId) {
        int points = loyaltyRepository.getPointsBalanceByVehicleId(vehicleId);

        return Map.of(
                "vehicleId", vehicleId,
                "pointsAvailable", points
        );
    }
}
