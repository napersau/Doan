package com.vetclinic.billing.testsupport;

import com.vetclinic.billing.constant.VisitStatus;
import com.vetclinic.billing.dto.response.VisitSummaryResponse;
import com.vetclinic.billing.entity.Medicine;
import com.vetclinic.billing.entity.Prescription;
import com.vetclinic.billing.entity.PrescriptionItem;
import com.vetclinic.common.exception.AppException;
import com.vetclinic.common.exception.ErrorCode;
import com.vetclinic.common.security.RoleCode;
import org.assertj.core.api.ThrowableAssert;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.util.UUID;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

public final class TestData {

    /** 2026-10-30 09:00 giờ Việt Nam. */
    public static final Clock CLOCK = Clock.fixed(Instant.parse("2026-10-30T02:00:00Z"), ZoneId.of("Asia/Ho_Chi_Minh"));

    public static final UUID OWNER = uid(5);
    public static final UUID RECEPTIONIST = uid(9);
    public static final UUID DOCTOR = uid(21);
    public static final UUID OTHER_DOCTOR = uid(22);

    private TestData() {
    }

    /** UUID demo cố định, cùng dạng với dữ liệu seed (019a9442-d810-7000-8000-0000000000NN). */
    public static UUID uid(long n) {
        return UUID.fromString(String.format("019a9442-d810-7000-8000-%012d", n));
    }

    public static Medicine medicine(long id, String name, String price, int stock) {
        return Medicine.builder().id(id).code("MED_" + id).name(name).dosageForm("Viên nén").unit("Viên")
                .salePrice(new BigDecimal(price)).vatRate(new BigDecimal("0.05")).stockQuantity(stock).minStockAlert(10).active(true).version(0L).build();
    }

    public static VisitSummaryResponse visit(long id, VisitStatus status, UUID doctorId) {
        return new VisitSummaryResponse(id, "VIS-2026-" + id, status, uid(11), "Lu", OWNER, doctorId);
    }

    public static PrescriptionItem item(long id, Medicine medicine, int quantity) {
        return PrescriptionItem.builder().id(id).medicine(medicine).quantity(quantity).dosage("1 viên")
                .frequency("2 lần/ngày").durationDays(5).build();
    }

    public static Prescription prescription(long id, long visitId, PrescriptionItem... items) {
        Prescription prescription = Prescription.builder().id(id).visitId(visitId).build();
        for (PrescriptionItem item : items) {
            prescription.addItem(item);
        }
        return prescription;
    }

    public static void loginAs(UUID userId, RoleCode role) {
        SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(
                userId.toString(), null, List.of(new SimpleGrantedAuthority("ROLE_" + role.name()))));
    }

    public static void assertError(ErrorCode expected, ThrowableAssert.ThrowingCallable call) {
        assertThatThrownBy(call)
                .isInstanceOf(AppException.class)
                .extracting(e -> ((AppException) e).getErrorCode()).isEqualTo(expected);
    }
}
