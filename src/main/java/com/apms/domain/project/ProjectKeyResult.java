package com.apms.domain.project;

import com.apms.common.enums.ProjectKeyResultType;
import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

import java.time.LocalDateTime;

@Entity
@Table(name = "project_key_results")
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ProjectKeyResult {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "project_id", nullable = false)
    @ToString.Exclude
    @EqualsAndHashCode.Exclude
    private Project project;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 50)
    private ProjectKeyResultType type;

    @org.hibernate.annotations.Nationalized
    @Column(nullable = false, length = 255)
    private String name;

    @org.hibernate.annotations.Nationalized
    @Column(columnDefinition = "NVARCHAR(MAX)")
    private String description;

    @Column(nullable = false)
    private Integer weight; // e.g., 20 for 20%

    @CreationTimestamp
    @Column(updatable = false)
    private LocalDateTime createdAt;

    @UpdateTimestamp
    private LocalDateTime updatedAt;
}
