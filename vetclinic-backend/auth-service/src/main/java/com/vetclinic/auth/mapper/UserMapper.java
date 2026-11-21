package com.vetclinic.auth.mapper;

import com.vetclinic.auth.dto.response.UserResponse;
import com.vetclinic.auth.dto.response.UserSummaryResponse;
import com.vetclinic.auth.entity.Role;
import com.vetclinic.auth.entity.StaffProfile;
import com.vetclinic.auth.entity.User;
import com.vetclinic.auth.entity.UserProfile;
import com.vetclinic.common.security.RoleCode;
import org.springframework.stereotype.Component;

import java.util.Comparator;
import java.util.List;

/**
 * Ghép tài khoản + hồ sơ cá nhân + hồ sơ nhân sự thành DTO. Viết tay (không dùng MapStruct) vì dữ liệu nằm trên nhiều thực thể.
 * Phải gọi trong transaction hoặc với User đã nạp kèm vai trò / hồ sơ (open-in-view đã tắt).
 */
@Component
public class UserMapper {

    public UserResponse toResponse(User user) {
        UserProfile profile = user.getProfile();
        List<RoleCode> roles = rolesOf(user);
        return new UserResponse(
                user.getId(),
                user.getUsername(),
                profile == null ? null : profile.getFullName(),
                profile == null ? null : profile.getPhoneNumber(),
                profile == null ? null : profile.getEmail(),
                profile == null ? null : profile.getAddress(),
                roomOf(user),
                roles.isEmpty() ? null : roles.get(0),
                roles,
                user.getStatus(),
                user.getCreatedAt(),
                user.getUpdatedAt());
    }

    public UserSummaryResponse toSummary(User user) {
        UserProfile profile = user.getProfile();
        List<RoleCode> roles = rolesOf(user);
        return new UserSummaryResponse(
                user.getId(),
                profile == null ? null : profile.getFullName(),
                profile == null ? null : profile.getPhoneNumber(),
                profile == null ? null : profile.getEmail(),
                roomOf(user),
                roles.isEmpty() ? null : roles.get(0),
                roles,
                user.getStatus());
    }

    /** Vai trò của người dùng theo thứ tự khai báo của {@link RoleCode} (vai trò chính đứng đầu). */
    public static List<RoleCode> rolesOf(User user) {
        return user.getRoles().stream().map(Role::getCode).sorted(Comparator.naturalOrder()).toList();
    }

    private static String roomOf(User user) {
        StaffProfile staff = user.getStaffProfile();
        return staff == null ? null : staff.getRoomNumber();
    }
}
