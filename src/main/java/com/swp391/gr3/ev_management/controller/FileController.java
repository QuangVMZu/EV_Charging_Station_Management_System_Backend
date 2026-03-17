package com.swp391.gr3.ev_management.controller;

import com.swp391.gr3.ev_management.dto.request.CreatePresignedUploadRequest;
import com.swp391.gr3.ev_management.dto.response.FileResponseDTO;
import com.swp391.gr3.ev_management.service.S3Service;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/files")
@CrossOrigin(origins = "*") // Update this to match your frontend origin in production
public class FileController {

    private final S3Service s3Service;

    public FileController(S3Service s3Service) {
        this.s3Service = s3Service;
    }

    @PostMapping("/upload-url")
    public ResponseEntity<FileResponseDTO> getUploadUrl(
            @RequestBody CreatePresignedUploadRequest request) {
        return ResponseEntity.ok(s3Service.generatePresignedUploadUrl(request));
    }

    @DeleteMapping("/delete")
    public ResponseEntity<String> deleteFile(@RequestParam("s3Key") String s3Key) {
        s3Service.deleteFile(s3Key);
        return ResponseEntity.ok("File deleted successfully");
    }

    @GetMapping("/url")
    public ResponseEntity<String> getFileUrl(@RequestParam("s3Key") String s3Key) {
        String publicUrl = s3Service.getFilePublicUrl(s3Key);
        return ResponseEntity.ok(publicUrl);
    }
}
