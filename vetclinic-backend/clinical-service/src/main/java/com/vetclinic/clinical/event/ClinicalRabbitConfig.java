package com.vetclinic.clinical.event;

import com.vetclinic.common.event.RabbitCommonConfig;
import com.vetclinic.common.event.EventRouting;
import org.springframework.amqp.core.Binding;
import org.springframework.amqp.core.BindingBuilder;
import org.springframework.amqp.core.DirectExchange;
import org.springframework.amqp.core.Queue;
import org.springframework.amqp.core.TopicExchange;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Queue của clinical-service (mẫu &lt;service&gt;.&lt;routing-key&gt;, bền vững, có DLQ). clinical NHẬN các sự kiện của auth-service để giữ
 * snapshot đúng (Kiến trúc v4.0 §5.2): {@code user.profile_updated} (bước 4.1: tên / SĐT chủ nuôi trên thú cưng).
 */
@Configuration
public class ClinicalRabbitConfig {

    public static final String SERVICE_NAME = "clinical-service";
    // Hằng số compile-time (dùng trong @RabbitListener)
    public static final String USER_PROFILE_UPDATED_QUEUE = SERVICE_NAME + "." + EventRouting.USER_PROFILE_UPDATED;

    @Bean
    public Queue userProfileUpdatedQueue() {
        return RabbitCommonConfig.durableQueueWithDlq(USER_PROFILE_UPDATED_QUEUE);
    }

    @Bean
    public Queue userProfileUpdatedDeadLetterQueue() {
        return RabbitCommonConfig.deadLetterQueue(USER_PROFILE_UPDATED_QUEUE);
    }

    @Bean
    public Binding userProfileUpdatedBinding(TopicExchange clinicEventsExchange) {
        return BindingBuilder.bind(userProfileUpdatedQueue()).to(clinicEventsExchange).with(EventRouting.USER_PROFILE_UPDATED);
    }

    @Bean
    public Binding userProfileUpdatedDeadLetterBinding(DirectExchange clinicEventsDeadLetterExchange) {
        return BindingBuilder.bind(userProfileUpdatedDeadLetterQueue()).to(clinicEventsDeadLetterExchange)
                .with(USER_PROFILE_UPDATED_QUEUE);
    }
}
