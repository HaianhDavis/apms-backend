package com.apms.domain.user;

import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

import java.time.LocalDateTime;

@Entity
@Table(name = "users")
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class UserProfile {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @OneToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "account_id", nullable = false, unique = true)
    @ToString.Exclude
    @EqualsAndHashCode.Exclude
    private Account account;

    @org.hibernate.annotations.Nationalized
    @Column(length = 255)
    private String firstName;

    @org.hibernate.annotations.Nationalized
    @Column(length = 255)
    private String lastName;

    @Column(length = 50)
    private String phone;

    @org.hibernate.annotations.Nationalized
    @Column(length = 255)
    private String department;

    @org.hibernate.annotations.Nationalized
    @Column(length = 255)
    private String position;

    @Column(length = 255)
    private String avatarUrl;

    @org.hibernate.annotations.Nationalized
    @Column(length = 255)
    private String address;

    @org.hibernate.annotations.Nationalized
    @Column(columnDefinition = "NVARCHAR(MAX)")
    private String bio;

    @CreationTimestamp
    @Column(updatable = false)
    private LocalDateTime createdAt;

    @UpdateTimestamp
    private LocalDateTime updatedAt;
}
