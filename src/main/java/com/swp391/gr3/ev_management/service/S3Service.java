package com.swp391.gr3.ev_management.service;

import com.swp391.gr3.ev_management.dto.request.CreatePresignedUploadRequest;
import com.swp391.gr3.ev_management.dto.response.FileResponseDTO;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.DeleteObjectRequest;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;
import software.amazon.awssdk.services.s3.presigner.S3Presigner;
import software.amazon.awssdk.services.s3.presigner.model.PresignedPutObjectRequest;
import software.amazon.awssdk.services.s3.presigner.model.PutObjectPresignRequest;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.LocalDate;
import java.util.Set;
import java.util.UUID;

@Service
public class S3Service {

    private final S3Presigner s3Presigner;
    private final S3Client s3Client;

    @Value("${aws.s3.bucket}")
    private String bucketName;
    
    @Value("${aws.cloudfront-base-url}")
    private String cloudfrontBaseUrl;

    public S3Service(S3Presigner s3Presigner, S3Client s3Client) {
        this.s3Presigner = s3Presigner;
        this.s3Client = s3Client;
    }

    public FileResponseDTO generatePresignedUploadUrl(CreatePresignedUploadRequest request) {
       validate(request);

        System.out.println("fileName = " + request.getFileName());
        System.out.println("contentType = " + request.getContentType());


        String folder = normalizeFolder(request.getFolder());
        String safeFileName = sanitizeFileName(request.getFileName());
        LocalDate today = LocalDate.now();

        String key = String.format(
                "%s/%d/%02d/%02d/%s-%s",
                folder,
                today.getYear(),
                today.getMonthValue(),
                today.getDayOfMonth(),
                UUID.randomUUID(),
                safeFileName
        );

        PutObjectRequest objectRequest = PutObjectRequest.builder()
                .bucket(bucketName)
                .key(key)
                .contentType(request.getContentType())
                .build();

        PutObjectPresignRequest presignRequest = PutObjectPresignRequest.builder()
                .signatureDuration(Duration.ofMinutes(10))
                .putObjectRequest(objectRequest)
                .build();

        PresignedPutObjectRequest presignedRequest = s3Presigner.presignPutObject(presignRequest);
        
        String presignedUrl = presignedRequest.url().toString();
        String publicUrl = cloudfrontBaseUrl + "/" + encodeKeyPath(key);

        return new FileResponseDTO(presignedUrl, key, publicUrl);
    }

    public void deleteFile(String s3Key) {
        DeleteObjectRequest deleteRequest = DeleteObjectRequest.builder()
                .bucket(bucketName)
                .key(s3Key)
                .build();
        s3Client.deleteObject(deleteRequest);
    }

    public String getFilePublicUrl(String s3Key) {
        return cloudfrontBaseUrl + "/" + s3Key;
    }



//    ==============================HELPER==============================
private void validate(CreatePresignedUploadRequest request) {


    System.out.println("fileName = " + request.getFileName());
    System.out.println("contentType = " + request.getContentType());

    if (request.getFileName() == null || request.getFileName().isBlank()) {
        throw new IllegalArgumentException("thiếu tên file");
    }
    if (request.getContentType() == null || request.getContentType().isBlank()) {
        throw new IllegalArgumentException("thiếu contentType");
    }
    if (
            !request.getContentType().startsWith("image/") &&
                    !request.getContentType().equals("application/pdf")
    ) {
        throw new IllegalArgumentException("Chỉ cho phép file ảnh hoặc PDF");
    }
}

private String normalizeFolder(String folder) {
    if (folder == null || folder.isBlank()) {
        return "uploads";
    }
    return folder.replaceAll("^/+", "").replaceAll("/+$", "");
}

    private String sanitizeFileName(String fileName) {
        return fileName.replaceAll("[^a-zA-Z0-9._-]", "_");
    }

    private String encodeKeyPath(String key) {
        return URLEncoder.encode(key, StandardCharsets.UTF_8)
                .replace("+", "%20")
                .replace("%2F", "/");
    }
}
