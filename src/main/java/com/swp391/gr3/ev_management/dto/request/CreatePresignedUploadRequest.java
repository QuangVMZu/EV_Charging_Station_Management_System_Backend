package com.swp391.gr3.ev_management.dto.request;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class CreatePresignedUploadRequest {
    private String fileName;
    private String contentType;
    private String folder;
}