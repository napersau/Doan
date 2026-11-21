package com.vetclinic.clinical.event;

import com.vetclinic.clinical.service.PetService;
import com.vetclinic.common.event.UserProfileUpdatedEvent;
import com.vetclinic.common.event.processed.IdempotentConsumer;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.stereotype.Component;

/**
 * auth-service đổi họ tên / SĐT của một tài khoản: cập nhật {@code pets.owner_name_snapshot / owner_phone_snapshot} của mọi thú cưng
 * thuộc chủ đó. Idempotent: {@link IdempotentConsumer} ghi {@code processed_events} cùng giao dịch với việc cập nhật; nhận trùng thì bỏ
 * qua. Lỗi thì retry 3 lần rồi vào DLQ. Tài khoản không có thú cưng (nhân viên) thì câu UPDATE không ảnh hưởng dòng nào.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class UserProfileUpdatedListener {

    /** Tên listener ghi vào processed_events.consumer. */
    public static final String CONSUMER = "clinical.user-profile-updated";

    private final PetService petService;
    private final IdempotentConsumer idempotentConsumer;

    @RabbitListener(queues = ClinicalRabbitConfig.USER_PROFILE_UPDATED_QUEUE)
    public void onUserProfileUpdated(UserProfileUpdatedEvent event) {
        idempotentConsumer.run(event.eventId(), CONSUMER, () -> {
            int updated = petService.refreshOwnerSnapshot(event.userId(), event.fullName(), event.phoneNumber());
            if (updated > 0) {
                log.info("Cập nhật snapshot chủ nuôi {} trên {} thú cưng", event.userId(), updated);
            }
        });
    }
}
