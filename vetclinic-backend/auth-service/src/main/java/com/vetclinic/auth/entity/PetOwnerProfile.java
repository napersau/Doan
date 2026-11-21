package com.vetclinic.auth.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.MapsId;
import jakarta.persistence.OneToOne;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.hibernate.annotations.CreationTimestamp;

import java.time.Instant;
import java.util.UUID;

/** Hồ sơ khách hàng / chủ nuôi (docs v4.0 mục 7), quan hệ 1-1 với {@link User}. */
@Entity
@Table(name = "pet_owner_profiles")
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class PetOwnerProfile {

    public static final String CHANNEL_ONLINE = "ONLINE";
    public static final String CHANNEL_AT_CLINIC = "AT_CLINIC";

    @Id
    @Column(name = "user_id")
    private UUID userId;

    @MapsId
    @OneToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "user_id")
    private User user;

    @Column(name = "customer_code", nullable = false, unique = true, length = 20)
    private String customerCode;

    /** ONLINE (tự đăng ký) | AT_CLINIC (Lễ tân / Admin tạo tại quầy). */
    @Column(name = "registered_channel", nullable = false, length = 20)
    @Builder.Default
    private String registeredChannel = CHANNEL_ONLINE;

    /** PHONE | EMAIL | APP. */
    @Column(name = "preferred_contact", nullable = false, length = 20)
    @Builder.Default
    private String preferredContact = "PHONE";

    @Column(name = "notes", columnDefinition = "TEXT")
    private String notes;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;
}
