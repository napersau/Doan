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

USE pet_clinic_clinical_db;
-- species_id: 1 = Chó (DOG), 2 = Mèo (CAT) theo seed của schema_init.sql
INSERT INTO pets (id, owner_id, species_id, pet_code, name, breed, gender) VALUES
(1, 8, 2, 'PET-2026-0001', 'Lu',   'Mèo Anh lông ngắn', 'FEMALE'),
(2, 8, 1, 'PET-2026-0002', 'Milo', 'Poodle',            'MALE');
