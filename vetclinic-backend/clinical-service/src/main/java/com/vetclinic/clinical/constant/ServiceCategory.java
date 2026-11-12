package com.vetclinic.clinical.constant;

import java.util.EnumSet;
import java.util.Set;

/** Khớp ENUM services.category. */
public enum ServiceCategory {
    EXAMINATION,
    VACCINATION,
    DEWORMING,
    EXTERNAL_PARASITE,
    INJECTION_INFUSION,
    LABORATORY,
    IMAGING,
    SURGERY,
    PROCEDURE;

    /** Dịch vụ cận lâm sàng: đi qua hàng đợi Lab / CĐHA. */
    public static final Set<ServiceCategory> PARACLINICAL = EnumSet.of(LABORATORY, IMAGING);

    /**
     * Dịch vụ THỰC HIỆN TẠI PHÒNG khám (FR-30): bác sĩ chỉ định rồi bấm "Đã thực hiện", không qua hàng đợi Lab / CĐHA. Phí khám
     * (EXAMINATION) là một dịch vụ riêng, tự tạo khi bắt đầu khám, không thuộc nhóm này.
     */
    public static final Set<ServiceCategory> IN_ROOM =
            EnumSet.of(VACCINATION, DEWORMING, EXTERNAL_PARASITE, INJECTION_INFUSION, SURGERY, PROCEDURE);

    public boolean isInRoom() {
        return IN_ROOM.contains(this);
    }

    public boolean isParaclinical() {
        return PARACLINICAL.contains(this);
    }
}
