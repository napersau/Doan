package com.vetclinic.clinical.constant;

/** Khớp ENUM queue_tickets.queue_type (Enum #11): hàng đợi phòng Bác sĩ, phòng Xét nghiệm, phòng CĐHA. */
public enum QueueType {
    DOCTOR,
    LABORATORY,
    IMAGING;

    /** Loại hàng đợi phòng cho một nhóm dịch vụ cận lâm sàng (LABORATORY / IMAGING). */
    public static QueueType ofRoom(ServiceCategory category) {
        return switch (category) {
            case LABORATORY -> LABORATORY;
            case IMAGING -> IMAGING;
            default -> throw new IllegalArgumentException("Không có hàng đợi phòng cho nhóm " + category);
        };
    }

    /** Nhóm dịch vụ của hàng đợi phòng (null với hàng đợi Bác sĩ). */
    public ServiceCategory category() {
        return switch (this) {
            case LABORATORY -> ServiceCategory.LABORATORY;
            case IMAGING -> ServiceCategory.IMAGING;
            case DOCTOR -> null;
        };
    }
}
