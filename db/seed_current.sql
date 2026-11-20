-- ==============================================================================
-- DỮ LIỆU DEMO TRUNG GIAN CỦA LỘ TRÌNH v4 (db/seed_current.sql) - chạy sau db/schema_current.sql.
-- Xuất phát: docs/seed_demo_data.sql tại tag v3-final. Đổi theo từng bước của LO_TRINH_SUA_CODE_V4.md
-- (UUID cố định theo quyết định D11); bước 8.2 viết lại docs/seed_demo_data.sql v4.0 từ file này.
-- ==============================================================================

-- ==============================================================================
-- SEED DỮ LIỆU DEMO (chỉ dùng cho môi trường dev/demo, chạy SAU schema_init.sql)
-- Bảng ID cố định theo LICH_TRINH_XAY_DUNG_MA_NGUON_VA_HUONG_DAN_TEST.md, Task 1.2.3.
-- Mọi lệnh curl kiểm thử trong Lộ trình dựa trên các ID này.
--
-- Mật khẩu chung của MỌI tài khoản demo (kể cả admin): Admin@123
-- (cùng hash BCrypt strength 12 với admin trong schema_init.sql)
--
--  id | username        | vai trò (role_id)       | phòng
--   1 | admin           | ADMIN (1)               | -      (đã có trong schema_init.sql)
--   2 | bs_tien         | VETERINARIAN (2)        | P101
--   3 | bs_an           | VETERINARIAN (2)        | P102
--   4 | bs_mai          | VETERINARIAN (2)        | P103
--   5 | letan_hoa       | RECEPTIONIST (3)        | -
--   6 | ktv_lab_minh    | LAB_TECHNICIAN (4)      | P201
--   7 | ktv_cdha_dung   | IMAGING_TECHNICIAN (5)  | P202
--   8 | khach_lan       | PET_OWNER (6)           | -      (chủ thú cưng #1 Lu, #2 Milo)
--   9 | khach_nam       | PET_OWNER (6)           | -      (thú cưng #3 Bông, #4 Mực)           [mở rộng 12/11/2026]
--  10 | khach_huong     | PET_OWNER (6)           | -      (thú cưng #5 Kitty, #6 Tom, #7 Luna)
--  11 | khach_duc       | PET_OWNER (6)           | -      (thú cưng #8 Béo, #9 Sóc)
--  12 | khach_thao      | PET_OWNER (6)           | -      (thú cưng #10 Vàng, #11 Mimi)
--  13 | khach_binh      | PET_OWNER (6)           | -      (thú cưng #12 Rex, #13 Nâu, #14 Cam)
-- LƯU Ý: seed CHỈ thêm người dùng và thú cưng (id >= 9 / >= 3). KHÔNG thêm lịch hẹn, đợt khám, hóa đơn hay biến động kho, để các ID
-- cố định trong Lộ trình vẫn đúng (đợt khám #1, lịch hẹn #1, Clavamox tồn 200 -> 190, hóa đơn thuốc #1).
-- ==============================================================================
SET NAMES utf8mb4;

USE pet_clinic_auth_db;
INSERT INTO users (id, role_id, username, password_hash, full_name, phone_number, room_number, status) VALUES
(2, 2, 'bs_tien',       '$2a$12$QfxJtMwsg2Wd72ZkvcQ.BeADCJqgMjJ7OPeBqNkj1um98Jt/dcBPG', 'Nguyễn Văn Tiến', '0902000002', 'P101', 'ACTIVE'),
(3, 2, 'bs_an',         '$2a$12$QfxJtMwsg2Wd72ZkvcQ.BeADCJqgMjJ7OPeBqNkj1um98Jt/dcBPG', 'Trần Bình An',    '0902000003', 'P102', 'ACTIVE'),
(4, 2, 'bs_mai',        '$2a$12$QfxJtMwsg2Wd72ZkvcQ.BeADCJqgMjJ7OPeBqNkj1um98Jt/dcBPG', 'Lê Thị Mai',      '0902000004', 'P103', 'ACTIVE'),
(5, 3, 'letan_hoa',     '$2a$12$QfxJtMwsg2Wd72ZkvcQ.BeADCJqgMjJ7OPeBqNkj1um98Jt/dcBPG', 'Phạm Thu Hoa',    '0902000005', NULL,   'ACTIVE'),
(6, 4, 'ktv_lab_minh',  '$2a$12$QfxJtMwsg2Wd72ZkvcQ.BeADCJqgMjJ7OPeBqNkj1um98Jt/dcBPG', 'Đỗ Quang Minh',   '0902000006', 'P201', 'ACTIVE'),
(7, 5, 'ktv_cdha_dung', '$2a$12$QfxJtMwsg2Wd72ZkvcQ.BeADCJqgMjJ7OPeBqNkj1um98Jt/dcBPG', 'Vũ Tiến Dũng',    '0902000007', 'P202', 'ACTIVE'),
(8, 6, 'khach_lan',     '$2a$12$QfxJtMwsg2Wd72ZkvcQ.BeADCJqgMjJ7OPeBqNkj1um98Jt/dcBPG', 'Hoàng Thị Lan',   '0912000008', NULL,   'ACTIVE');

-- Thêm chủ nuôi (mật khẩu chung Admin@123) để demo danh sách khách hàng và đặt lịch nhiều người
INSERT INTO users (id, role_id, username, password_hash, full_name, phone_number, email, status) VALUES
(9, 6, 'khach_nam', '$2a$12$QfxJtMwsg2Wd72ZkvcQ.BeADCJqgMjJ7OPeBqNkj1um98Jt/dcBPG', 'Nguyễn Hoàng Nam', '0912000009', 'nam.demo@example.com', 'ACTIVE'),
(10, 6, 'khach_huong', '$2a$12$QfxJtMwsg2Wd72ZkvcQ.BeADCJqgMjJ7OPeBqNkj1um98Jt/dcBPG', 'Lê Thị Hương', '0912000010', 'huong.demo@example.com', 'ACTIVE'),
(11, 6, 'khach_duc', '$2a$12$QfxJtMwsg2Wd72ZkvcQ.BeADCJqgMjJ7OPeBqNkj1um98Jt/dcBPG', 'Trần Minh Đức', '0912000011', NULL, 'ACTIVE'),
(12, 6, 'khach_thao', '$2a$12$QfxJtMwsg2Wd72ZkvcQ.BeADCJqgMjJ7OPeBqNkj1um98Jt/dcBPG', 'Phạm Thanh Thảo', '0912000012', 'thao.demo@example.com', 'ACTIVE'),
(13, 6, 'khach_binh', '$2a$12$QfxJtMwsg2Wd72ZkvcQ.BeADCJqgMjJ7OPeBqNkj1um98Jt/dcBPG', 'Võ Quốc Bình', '0912000013', NULL, 'ACTIVE');

USE pet_clinic_clinical_db;
-- species_id: 1 = Chó (DOG), 2 = Mèo (CAT) theo seed của schema_init.sql
INSERT INTO pets (id, owner_id, species_id, pet_code, name, breed, gender) VALUES
(1, 8, 2, 'PET-2026-0001', 'Lu',   'Mèo Anh lông ngắn', 'FEMALE'),
(2, 8, 1, 'PET-2026-0002', 'Milo', 'Poodle',            'MALE');

-- Thú cưng bổ sung (id 3-14) cho chủ nuôi id 9-13
INSERT INTO pets (id, owner_id, species_id, pet_code, name, breed, gender) VALUES
(3, 9, 1, 'PET-2026-0003', 'Bông', 'Poodle', 'FEMALE'),
(4, 9, 1, 'PET-2026-0004', 'Mực', 'Labrador', 'MALE'),
(5, 10, 2, 'PET-2026-0005', 'Kitty', 'Mèo Ba Tư', 'FEMALE'),
(6, 10, 2, 'PET-2026-0006', 'Tom', 'Mèo mướp', 'MALE'),
(7, 10, 2, 'PET-2026-0007', 'Luna', 'Mèo Anh lông dài', 'FEMALE'),
(8, 11, 1, 'PET-2026-0008', 'Béo', 'Corgi', 'MALE'),
(9, 11, 1, 'PET-2026-0009', 'Sóc', 'Phốc sóc', 'FEMALE'),
(10, 12, 1, 'PET-2026-0010', 'Vàng', 'Golden Retriever', 'MALE'),
(11, 12, 2, 'PET-2026-0011', 'Mimi', 'Mèo Xiêm', 'FEMALE'),
(12, 13, 1, 'PET-2026-0012', 'Rex', 'Becgie Đức', 'MALE'),
(13, 13, 1, 'PET-2026-0013', 'Nâu', 'Chó ta', 'UNKNOWN'),
(14, 13, 2, 'PET-2026-0014', 'Cam', 'Mèo tam thể', 'FEMALE');
