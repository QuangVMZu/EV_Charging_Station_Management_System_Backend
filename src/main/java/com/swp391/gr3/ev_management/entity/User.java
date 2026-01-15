package com.swp391.gr3.ev_management.entity;

import com.fasterxml.jackson.annotation.JsonFormat;
import com.fasterxml.jackson.annotation.JsonIgnore;
import jakarta.persistence.Entity;
import jakarta.persistence.*;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Past;
import jakarta.validation.constraints.Pattern;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

@Entity
@Table(name = "Users")
@Data @NoArgsConstructor @AllArgsConstructor @Builder
public class User {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "UserID")
    private Long userId;

    @Column(name = "Email", unique = true, nullable = false, columnDefinition = "NVARCHAR(255)")
    @NotBlank(message = "Email không được để trống")
    @Pattern(regexp = "^[A-Za-z0-9+_.-]+@(.+)$", message = "Email không đúng định dạng")
    private String email;

    @Column(name="phone_number", length=15, unique=true)
//    @NotBlank(message = "Số điện thoại không được để trống")
    @Pattern(regexp = "^(\\+84|0)\\d{9,10}$", message="Invalid VN phone")
    private String phoneNumber;

    @Column(name = "Password_hash", nullable = false, columnDefinition = "NVARCHAR(255)")
    private String passwordHash;

    @Column(name = "Name", columnDefinition = "NVARCHAR(50)", nullable = false)
    @NotBlank(message = "Họ tên không được để trống")
    private String name;

    @Column(name = "Date_of_birth")
//    @NotNull(message = "dateOfBirth is required")
    @Past(message = "dateOfBirth must be in the past")
    @JsonFormat(pattern = "yyyy-MM-dd")   // nhận "2025-10-11"
    private LocalDate dateOfBirth;

    @Column(name = "Gender", columnDefinition = "NVARCHAR(1)")
//    @NotBlank(message = "Giới tính không được để trống")
    @Pattern(regexp = "^[MF]$", message = "Giới tính không đúng định dạng")
    private String gender;

    @Column(name = "Address", columnDefinition = "NVARCHAR(50)")
//    @NotBlank(message = "Địa chỉ không được để trống")
    private String address;

    @CreationTimestamp
    @Column(name = "CreatedAt", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @UpdateTimestamp
    @Column(name = "UpdatedAt", nullable = false)
    private LocalDateTime updatedAt;

    // BR-01: Account locking after 3 failed attempts
    // Note: Failed login tracking được xử lý bằng in-memory cache (LoginAttemptService)
    // để tránh thay đổi cấu trúc database

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "RoleID")
    private Role role;

    @OneToOne(mappedBy = "user", fetch = FetchType.LAZY)
    private Driver driver;

    @OneToOne(mappedBy = "user", fetch = FetchType.LAZY)
    private Admin admin;

    @OneToOne(mappedBy = "user", fetch = FetchType.LAZY)
    private Staffs staffs;

    @OneToMany(mappedBy = "user", fetch = FetchType.LAZY)
    @JsonIgnore  // 👈 rất quan trọng
    private List<Notification> notifications;


    @PrePersist
    public void prePersist() {
        if (this.dateOfBirth == null) {
            // Ví dụ đặt ngày mặc định là 2000-01-01
            this.dateOfBirth = LocalDate.of(2000, 1, 1);
        }
    }

}
