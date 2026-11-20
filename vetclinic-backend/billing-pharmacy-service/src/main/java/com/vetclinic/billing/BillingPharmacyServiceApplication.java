package com.vetclinic.billing;

import com.vetclinic.common.event.outbox.EnableOutbox;
import com.vetclinic.common.util.TimeZones;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;
import org.springframework.cloud.openfeign.EnableFeignClients;

// @EnableOutbox: MedicineInvoicePaid / MedicineLowStock đi qua Transactional Outbox (bảng outbox_events)
@EnableOutbox
@ConfigurationPropertiesScan
@EnableFeignClients(basePackages = "com.vetclinic.billing.client")
@SpringBootApplication(scanBasePackages = "com.vetclinic")
public class BillingPharmacyServiceApplication {

    public static void main(String[] args) {
        // JVM phải ở UTC để khớp phiên JDBC (xem TimeZones); giờ phòng khám tính tường minh bằng Clock
        TimeZones.forceUtcDefault();
        SpringApplication.run(BillingPharmacyServiceApplication.class, args);
    }
}
