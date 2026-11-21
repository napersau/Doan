package com.vetclinic.auth.repository;

import com.vetclinic.auth.constant.UserStatus;
import com.vetclinic.auth.entity.User;
import com.vetclinic.common.security.RoleCode;
import jakarta.persistence.criteria.Join;
import jakarta.persistence.criteria.Predicate;
import org.springframework.data.jpa.domain.Specification;

import java.util.ArrayList;
import java.util.List;

public final class UserSpecifications {

    private UserSpecifications() {
    }

    /**
     * Lọc theo vai trò, trạng thái và từ khóa (họ tên / SĐT / tên đăng nhập). Tham số null thì bỏ qua điều kiện.
     * Họ tên và SĐT nằm ở user_profiles nên nối bảng hồ sơ; vai trò nối user_roles (mỗi người chỉ khớp một dòng cho một mã vai trò).
     */
    public static Specification<User> filter(RoleCode role, UserStatus status, String keyword) {
        return (root, query, cb) -> {
            List<Predicate> predicates = new ArrayList<>();
            if (role != null) {
                Join<Object, Object> roles = root.join("roles");
                predicates.add(cb.equal(roles.get("code"), role));
            }
            if (status != null) {
                predicates.add(cb.equal(root.get("status"), status));
            }
            if (keyword != null && !keyword.isBlank()) {
                String like = "%" + escapeLike(keyword.trim().toLowerCase()) + "%";
                Join<Object, Object> profile = root.join("profile");
                predicates.add(cb.or(
                        cb.like(cb.lower(profile.get("fullName")), like, '\\'),
                        cb.like(cb.lower(root.get("username")), like, '\\'),
                        cb.like(profile.get("phoneNumber"), like, '\\')));
            }
            return cb.and(predicates.toArray(Predicate[]::new));
        };
    }

    // Người dùng gõ % hoặc _ không được biến thành ký tự đại diện
    private static String escapeLike(String value) {
        return value.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_");
    }
}
