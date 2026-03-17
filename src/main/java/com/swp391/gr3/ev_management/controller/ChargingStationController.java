package com.swp391.gr3.ev_management.controller;

import com.swp391.gr3.ev_management.dto.request.ChargingStationRequest;
import com.swp391.gr3.ev_management.dto.response.ChargingStationResponse;
import com.swp391.gr3.ev_management.enums.ChargingStationStatus;
import com.swp391.gr3.ev_management.service.ChargingStationService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController // ✅ Đánh dấu đây là REST Controller (trả dữ liệu JSON)
@RequestMapping("/api/charging-stations") // ✅ Prefix chung cho các endpoint: /api/charging-stations
@RequiredArgsConstructor // ✅ Lombok: tự động tạo constructor cho field final (DI)
@Tag(name = "Charging Station", description = "APIs for managing charging stations") // ✅ Dùng cho Swagger mô tả nhóm API
public class ChargingStationController {

    private final ChargingStationService chargingStationService; // ✅ Service xử lý nghiệp vụ của trạm sạc

    // =========================================================================
    // ✅ 1. ADMIN: CẬP NHẬT TRẠM SẠC (PUT /{id})
    // =========================================================================
    @PreAuthorize("hasRole('ADMIN')") // 🔒 Chỉ ADMIN có quyền cập nhật trạm
    @PutMapping("/{id}") // 🔗 Endpoint: PUT /api/charging-stations/{id}
    @Operation(summary = "Update an existing charging station") // 📝 Mô tả API trên Swagger
    public ResponseEntity<ChargingStationResponse> updateStation(
            @PathVariable long id, // ✅ ID của trạm sạc cần cập nhật (lấy từ URL)
            @RequestBody ChargingStationRequest request // ✅ Dữ liệu cập nhật gửi từ client
    ) {
        // 🟢 Gọi service để cập nhật thông tin trạm sạc
        ChargingStationResponse updated = chargingStationService.updateChargingStation(id, request);

        // ❌ Nếu không tìm thấy trạm sạc -> trả 404 NOT FOUND
        if (updated == null) return ResponseEntity.notFound().build();

        // ✅ Trả về HTTP 200 OK + thông tin trạm đã cập nhật
        return ResponseEntity.ok(updated);
    }

    // =========================================================================
    // ✅ 2. ADMIN: CẬP NHẬT TRẠNG THÁI TRẠM SẠC (PUT /{id}/status)
    // =========================================================================
    @PreAuthorize("hasRole('ADMIN')") // 🔒 Chỉ ADMIN có quyền
    @PutMapping("/{id}/status") // 🔗 Endpoint: PUT /api/charging-stations/{id}/status?status=ACTIVE|INACTIVE|MAINTENANCE
    @Operation(summary = "Update a status of charging station") // 📝 Swagger mô tả
    public ResponseEntity<ChargingStationResponse> updateStatus(
            @PathVariable long id, // ✅ ID trạm sạc cần cập nhật
            @RequestParam("status") ChargingStationStatus newStatus // ✅ Trạng thái mới (lấy từ query param)
    ) {
        // 🟢 Gọi service để cập nhật trạng thái trạm sạc
        return ResponseEntity.ok(chargingStationService.updateStationStatus(id, newStatus));
    }

    // =========================================================================
    // ✅ 3. ADMIN: THÊM TRẠM SẠC MỚI (POST /)
    // =========================================================================
    @PreAuthorize("hasRole('ADMIN')") // 🔒 Chỉ ADMIN được thêm trạm mới
    @PostMapping // 🔗 Endpoint: POST /api/charging-stations
    @Operation(summary = "Add a new charging station") // 📝 Swagger mô tả API
    public ResponseEntity<ChargingStationResponse> addStation(@RequestBody ChargingStationRequest request) {
        // 🟢 Gọi service để thêm mới một trạm sạc
        ChargingStationResponse created = chargingStationService.addChargingStation(request);

        // 🟢 Trả về 200 OK cùng với thông tin trạm sạc vừa được tạo
        return ResponseEntity.ok(created);
    }

    // =========================================================================
    // ✅ 4. MỌI NGƯỜI: LẤY DANH SÁCH TẤT CẢ TRẠM SẠC (GET /)
    // =========================================================================
    @Operation(summary = "Get all charging stations") // 📝 Swagger mô tả API
    @GetMapping("/stations")
    public List<ChargingStationResponse> getStations(
            @RequestParam(required = false) Double lat,
            @RequestParam(required = false) Double lng
    ) {
        return chargingStationService.getAllStations(lat, lng);
    }

    // =========================================================================
    // ✅ 5. MỌI NGƯỜI: LẤY THÔNG TIN MỘT TRẠM SẠC CỤ THỂ (GET /{id})
    // =========================================================================
    @GetMapping("/{id}") // 🔗 Endpoint: GET /api/charging-stations/{id}
    @Operation(summary = "Get charging station by ID") // 📝 Swagger mô tả API
    public ResponseEntity<ChargingStationResponse> getStationById(@PathVariable long id) {
        // 🟢 Gọi service để lấy thông tin trạm sạc theo ID
        ChargingStationResponse response = chargingStationService.findByStationId(id);

        // ❌ Nếu không tồn tại -> trả về 404
        if (response == null) return ResponseEntity.notFound().build();

        // ✅ Nếu có -> trả về 200 OK cùng dữ liệu trạm sạc
        return ResponseEntity.ok(response);
    }
}
