# 16. Triển khai: chạy 13 container bằng một lệnh (Docker Compose)

## 1. Vấn đề

Muốn chạy hệ thống phải bật: MySQL, RabbitMQ, Redis, Mailpit, Eureka, Gateway, 6 service, giao diện. Mỗi cái cần đúng phiên bản Java,
đúng biến môi trường, đúng **thứ tự** (service phải chờ MySQL sẵn sàng). Làm tay trên mỗi máy thì mất cả buổi và mỗi máy một kiểu.

## 2. Lý thuyết

- **Docker image**: "hộp đóng gói" chứa chương trình + mọi thứ nó cần (Java 21, file jar). Chạy ở máy nào cũng như nhau.
- **Container**: một image đang chạy. Mỗi service một container, cô lập với nhau.
- **Docker Compose**: một file YAML mô tả **cả hệ thống** (container nào, biến môi trường gì, mạng, ổ đĩa, thứ tự khởi động). Một lệnh
  `docker compose up` dựng toàn bộ.
- **Healthcheck**: lệnh Docker chạy định kỳ để biết container đã **thực sự sẵn sàng** (không chỉ "đã chạy").

Microservice gần như luôn đi kèm container: mỗi service đóng gói, triển khai, khởi động lại **độc lập**.

## 3. Trong dự án

### 3.1. Một Dockerfile cho mọi service

[vetclinic-backend/Dockerfile](../vetclinic-backend/Dockerfile) dùng **build nhiều giai đoạn** (multi-stage):

```dockerfile
# Giai đoạn 1: biên dịch bằng Maven + JDK 21 (không cần cài Java trên máy)
FROM maven:3.9-eclipse-temurin-21 AS build
COPY . .
ARG MODULE                                   # tên module cần build, truyền từ docker-compose
RUN mvn -B -q -pl ${MODULE} -am -DskipTests package \
    && cp ${MODULE}/target/${MODULE}.jar /workspace/app.jar

# Giai đoạn 2: image chạy chỉ có JRE (nhỏ gọn), không có Maven / mã nguồn
FROM eclipse-temurin:21-jre
RUN groupadd --system vetclinic && useradd --system --gid vetclinic vetclinic   # không chạy bằng root
COPY --from=build /workspace/app.jar /app/app.jar
USER vetclinic
ENV JAVA_TOOL_OPTIONS="-XX:MaxRAMPercentage=75 -XX:+ExitOnOutOfMemoryError -Duser.timezone=UTC"
ENTRYPOINT ["java", "-jar", "/app/app.jar"]
```

`-pl ${MODULE} -am`: chỉ build module đó **và các module nó phụ thuộc** (common-library). Cùng một Dockerfile, đổi `MODULE` là ra image của
service khác.

### 3.2. File compose

[docker-compose.yml](../docker-compose.yml) (rút gọn):

```yaml
x-spring-env: &spring-env               # khối biến môi trường dùng chung (YAML anchor, tái sử dụng bằng <<: *spring-env)
  TZ: UTC
  EUREKA_URL: http://service-registry:8761/eureka   # gọi nhau bằng TÊN container
  DB_HOST: mysql-server
  RABBITMQ_HOST: rabbitmq-broker
  DB_PASSWORD: ${MYSQL_ROOT_PASSWORD:?Thiếu MYSQL_ROOT_PASSWORD trong .env}   # đọc từ file .env, thiếu thì báo lỗi

services:
  mysql-server:
    image: mysql:8.0
    volumes:
      - mysql-data:/var/lib/mysql                                        # dữ liệu nằm ở volume, xóa container không mất
      - ./docs/schema_init.sql:/docker-entrypoint-initdb.d/01-schema_init.sql:ro    # tạo 52 bảng lần đầu
      - ./docs/seed_demo_data.sql:/docker-entrypoint-initdb.d/02-seed_demo_data.sql:ro
    healthcheck:
      # healthy chỉ khi dữ liệu mẫu đã nạp xong (bảng pets có >= 2 dòng), không chỉ khi MySQL mở cổng
      test: ["CMD-SHELL", "n=$$(mysql ... -e 'SELECT COUNT(*) FROM pet_clinic_clinical_db.pets'); [ \"$${n:-0}\" -ge 2 ]"]

  appointment-service:
    build:
      context: ./vetclinic-backend
      args:
        MODULE: appointment-service           # Dockerfile chung, build module này
    environment:
      <<: *spring-env
    mem_limit: 512m
    depends_on:                                # chỉ khởi động khi các thành phần này HEALTHY
      service-registry: { condition: service_healthy }
      mysql-server:     { condition: service_healthy }
      rabbitmq-broker:  { condition: service_healthy }
    healthcheck:
      test: ["CMD", "curl", "-fsS", "http://localhost:8082/actuator/health"]

  api-gateway:
    ports:
      - "8080:8080"                            # CHỈ Gateway mở cổng ra máy host
    depends_on:                                # khởi động SAU CÙNG
      auth-service: { condition: service_healthy }
      appointment-service: { condition: service_healthy }
      ...
```

### 3.3. Thứ tự khởi động

```
mysql-server, rabbitmq-broker, redis, mailpit            (hạ tầng)
            │ healthy
            ▼
service-registry (Eureka)
            │ healthy
            ▼
auth, appointment, clinical, billing, notification, reporting   (khởi động song song, tự đăng ký Eureka)
            │ tất cả healthy
            ▼
api-gateway   ← lần đầu tải danh bạ Eureka đã có đủ service
            ▼
frontend (cổng 3000)
```

"api-gateway healthy" nghĩa là **cả hệ thống** sẵn sàng.

### 3.4. Bảo mật mạng

- Mọi container chung mạng nội bộ `vetclinic-net`, gọi nhau bằng tên (`mysql-server`, `auth-service`...).
- **Chỉ mở cổng 8080 (API) và 3000 (giao diện).** MySQL, RabbitMQ, Redis, các service con **không** mở cổng: service con tin header
  `X-User-*` (file 04), mở cổng = cho phép giả mạo quyền.
- File [docker-compose.dev.yml](../docker-compose.dev.yml) mở thêm cổng hạ tầng (3306, 5672, 15672, 8761, 8025) **chỉ khi phát triển**.
- Mật khẩu, `JWT_SECRET` nằm trong `.env`, không commit lên Git.

### 3.5. Các file compose phụ

| File | Dùng khi |
|---|---|
| `docker-compose.yml` | Chạy chuẩn |
| `docker-compose.dev.yml` | Mở cổng hạ tầng để xem RabbitMQ UI, Eureka, Mailpit hoặc chạy service trong IDE |
| `docker-compose.db.yml` | Mở MySQL ở cổng 3307 (máy đã có MySQL chiếm 3306) |
| `docker-compose.nomount.yml` | Docker Desktop không bind-mount được thư mục |

## 4. Thử tận tay

```bash
docker compose ps                         # 13 container, cột STATUS phải "healthy"
docker compose logs -f clinical-service   # xem log một service (Ctrl+C để thoát)
docker stats --no-stream                  # bộ nhớ từng container (mỗi service Java giới hạn 512 MB, giao diện 128 MB)

docker compose restart notification-service      # khởi động lại MỘT service, các service khác không bị ảnh hưởng
docker compose up -d --build reporting-service   # build lại và triển khai lại MỘT service sau khi sửa code

docker compose down -v                    # XÓA SẠCH (kể cả dữ liệu) – cần khi sửa schema_init.sql
docker compose up -d --build              # dựng lại từ đầu (~10 phút lần đầu)
```

## 5. Tự kiểm tra

1. Multi-stage build trong Dockerfile có lợi gì?
2. Vì sao một Dockerfile dùng được cho cả 8 module?
3. `depends_on` với `condition: service_healthy` khác `depends_on` thường thế nào?
4. Vì sao healthcheck của MySQL kiểm tra bảng `pets` thay vì chỉ ping?
5. Vì sao chỉ mở cổng 8080 và 3000?
6. Sửa `docs/schema_init.sql` rồi `docker compose up -d` thì CSDL có đổi không? Vì sao?
