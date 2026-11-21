package com.vetclinic.auth.service.impl;

import com.vetclinic.auth.constant.UserStatus;
import com.vetclinic.auth.dto.request.ChangePasswordRequest;
import com.vetclinic.auth.dto.request.CreateUserRequest;
import com.vetclinic.auth.dto.request.RegisterRequest;
import com.vetclinic.auth.dto.request.UpdateProfileRequest;
import com.vetclinic.auth.dto.request.UpdateUserRequest;
import com.vetclinic.auth.dto.response.UserResponse;
import com.vetclinic.auth.dto.response.UserSummaryResponse;
import com.vetclinic.auth.entity.PetOwnerProfile;
import com.vetclinic.auth.entity.Role;
import com.vetclinic.auth.entity.StaffProfile;
import com.vetclinic.auth.entity.User;
import com.vetclinic.auth.entity.UserProfile;
import com.vetclinic.auth.mapper.UserMapper;
import com.vetclinic.auth.repository.PetOwnerProfileRepository;
import com.vetclinic.auth.repository.RefreshTokenRepository;
import com.vetclinic.auth.repository.RoleRepository;
import com.vetclinic.auth.repository.StaffProfileRepository;
import com.vetclinic.auth.repository.UserProfileRepository;
import com.vetclinic.auth.repository.UserRepository;
import com.vetclinic.auth.repository.UserSpecifications;
import com.vetclinic.auth.service.AccountRules;
import com.vetclinic.auth.service.UserService;
import com.vetclinic.common.dto.PageResponse;
import com.vetclinic.common.exception.AppException;
import com.vetclinic.common.exception.ErrorCode;
import com.vetclinic.common.security.RoleCode;
import com.vetclinic.common.security.SecurityUtils;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.ZoneId;
import java.util.EnumSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;

@Slf4j
@Service
@RequiredArgsConstructor
public class UserServiceImpl implements UserService {

    /** Chỉ các vai trò làm việc tại phòng cố định mới có số phòng. */
    private static final Set<RoleCode> ROOM_ROLES =
            EnumSet.of(RoleCode.VETERINARIAN, RoleCode.LAB_TECHNICIAN, RoleCode.IMAGING_TECHNICIAN);
    private static final int MAX_PAGE_SIZE = 100;
    private static final int MAX_BATCH_IDS = 200;
    private static final ZoneId CLINIC_ZONE = ZoneId.of("Asia/Ho_Chi_Minh");

    private final UserRepository userRepository;
    private final RoleRepository roleRepository;
    private final UserProfileRepository userProfileRepository;
    private final StaffProfileRepository staffProfileRepository;
    private final PetOwnerProfileRepository petOwnerProfileRepository;
    private final RefreshTokenRepository refreshTokenRepository;
    private final PasswordEncoder passwordEncoder;
    private final UserMapper userMapper;

    // ------------------------------------------------------------------ người dùng hiện tại

    @Override
    @Transactional(readOnly = true)
    public UserResponse getMe() {
        User user = findCurrentUser();
        // Access Token còn hạn nhưng tài khoản đã bị khóa sau đó: không cho tiếp tục dùng
        AccountRules.assertUsable(user);
        return userMapper.toResponse(user);
    }

    @Override
    @Transactional
    public UserResponse updateMe(UpdateProfileRequest request) {
        User user = findCurrentUser();
        AccountRules.assertUsable(user);
        applyProfile(user, request.fullName(), request.phoneNumber(), request.email(), request.address());
        return userMapper.toResponse(saveAndFlush(user));
    }

    @Override
    @Transactional
    public void changePassword(ChangePasswordRequest request) {
        User user = findCurrentUser();
        AccountRules.assertUsable(user);

        if (AccountRules.exceedsBcryptLimit(request.currentPassword())
                || !passwordEncoder.matches(request.currentPassword(), user.getPasswordHash())) {
            throw new AppException(ErrorCode.INVALID_CURRENT_PASSWORD);
        }
        requireEncodablePassword(request.newPassword());
        if (request.currentPassword().equals(request.newPassword())) {
            throw new AppException(ErrorCode.INVALID_INPUT_DATA, "Mật khẩu mới phải khác mật khẩu hiện tại");
        }

        user.setPasswordHash(passwordEncoder.encode(request.newPassword()));
        userRepository.save(user);
        // flushAutomatically ghi mật khẩu mới xuống DB trước khi thu hồi token
        refreshTokenRepository.revokeAllUserTokens(user.getId());
        log.info("Người dùng userId={} đã đổi mật khẩu, thu hồi toàn bộ phiên", user.getId());
    }

    // ------------------------------------------------------------------ quản lý tài khoản

    @Override
    @Transactional
    public UserResponse create(CreateUserRequest request) {
        // Lễ tân (không kiêm Admin) chỉ được tạo tài khoản chủ nuôi (UC-18); chỉ ADMIN mới tạo nhân sự
        if (!actsAsAdmin() && request.roleCode() != RoleCode.PET_OWNER) {
            throw new AppException(ErrorCode.ACCESS_DENIED);
        }
        log.info("Tạo tài khoản: role={}, bởi userId={}", request.roleCode(), SecurityUtils.getCurrentUserId());
        return userMapper.toResponse(persistNewUser(request, PetOwnerProfile.CHANNEL_AT_CLINIC));
    }

    @Override
    @Transactional
    public UserResponse register(RegisterRequest request) {
        User user = persistNewUser(new CreateUserRequest(null, request.password(), request.fullName(), request.phoneNumber(),
                request.email(), request.address(), null, RoleCode.PET_OWNER), PetOwnerProfile.CHANNEL_ONLINE);
        log.info("Chủ nuôi tự đăng ký: userId={}", user.getId());
        return userMapper.toResponse(user);
    }

    /**
     * Dùng chung cho tạo bởi nhân viên và tự đăng ký; việc kiểm tra quyền người gọi nằm ở nơi gọi. Một giao dịch ghi
     * {@code users + user_roles + user_profiles} và hồ sơ theo loại (nhân sự hoặc khách hàng).
     */
    private User persistNewUser(CreateUserRequest request, String ownerChannel) {
        requireEncodablePassword(request.password());

        String phone = request.phoneNumber().trim();
        String username = hasText(request.username()) ? request.username().trim() : phone;
        String email = blankToNull(request.email());
        // Kiểm tra SĐT trước: khi tên đăng nhập mặc định là SĐT thì "trùng SĐT" là thông báo đúng với người dùng
        if (userProfileRepository.existsByPhoneNumber(phone)) {
            throw new AppException(ErrorCode.PHONE_NUMBER_ALREADY_EXISTS);
        }
        if (userRepository.existsByUsername(username)) {
            throw new AppException(ErrorCode.USERNAME_ALREADY_EXISTS);
        }
        if (email != null && userProfileRepository.existsByEmailIgnoreCase(email)) {
            throw new AppException(ErrorCode.INVALID_INPUT_DATA, "Email đã được sử dụng cho tài khoản khác");
        }
        String room = normalizeRoom(request.roomNumber(), EnumSet.of(request.roleCode()));
        if (room != null && staffProfileRepository.existsByRoomNumber(room)) {
            throw new AppException(ErrorCode.ROOM_NUMBER_ALREADY_ASSIGNED);
        }

        Role role = roleRepository.findByCode(request.roleCode())
                .orElseThrow(() -> new AppException(ErrorCode.ROLE_NOT_FOUND));
        User user = User.builder()
                .username(username)
                .passwordHash(passwordEncoder.encode(request.password()))
                .status(UserStatus.ACTIVE)
                .build();
        user.getRoles().add(role);
        UserProfile profile = UserProfile.builder()
                .user(user)
                .fullName(request.fullName().trim())
                .phoneNumber(phone)
                .email(email)
                .address(blankToNull(request.address()))
                .build();
        user.setProfile(profile);

        if (request.roleCode() == RoleCode.PET_OWNER) {
            user.setPetOwnerProfile(PetOwnerProfile.builder()
                    .user(user)
                    .customerCode(String.format("KH%04d", petOwnerProfileRepository.nextCustomerNumber()))
                    .registeredChannel(ownerChannel)
                    .build());
        } else {
            user.setStaffProfile(StaffProfile.builder()
                    .user(user)
                    .employeeCode(String.format("NV%04d", staffProfileRepository.nextEmployeeNumber()))
                    .positionTitle(role.getName())
                    .department(departmentOf(request.roleCode()))
                    .roomNumber(room)
                    .hireDate(LocalDate.now(CLINIC_ZONE))
                    .build());
        }
        return saveAndFlush(user);
    }

    @Override
    @Transactional(readOnly = true)
    public PageResponse<UserResponse> search(RoleCode role, UserStatus status, String keyword, int page, int size) {
        // Lễ tân chỉ tra cứu được khách hàng (FR-08), không xem danh sách nhân sự
        if (!actsAsAdmin()) {
            role = RoleCode.PET_OWNER;
        }
        PageRequest pageable = PageRequest.of(Math.max(page, 0), Math.min(Math.max(size, 1), MAX_PAGE_SIZE),
                Sort.by(Sort.Direction.DESC, "id"));   // UUIDv7 xếp được theo thời gian: mới nhất trước
        Page<User> result = userRepository.findAll(UserSpecifications.filter(role, status, keyword), pageable);
        return PageResponse.of(result.getContent(), result.getNumber(), result.getSize(), result.getTotalElements(),
                result.getTotalPages(), result.isLast(), userMapper::toResponse);
    }

    @Override
    @Transactional(readOnly = true)
    public UserResponse getById(UUID id) {
        return userMapper.toResponse(findById(id));
    }

    @Override
    @Transactional
    public UserResponse update(UUID id, UpdateUserRequest request) {
        User user = findById(id);
        applyProfile(user, request.fullName(), request.phoneNumber(), request.email(), request.address());

        if (request.roomNumber() != null) {
            Set<RoleCode> roles = EnumSet.noneOf(RoleCode.class);
            roles.addAll(UserMapper.rolesOf(user));
            String room = normalizeRoom(request.roomNumber(), roles);
            if (room != null && staffProfileRepository.existsByRoomNumberAndUserIdNot(room, user.getId())) {
                throw new AppException(ErrorCode.ROOM_NUMBER_ALREADY_ASSIGNED);
            }
            if (user.getStaffProfile() != null) {
                user.getStaffProfile().setRoomNumber(room);
            } else if (room != null) {
                throw new AppException(ErrorCode.INVALID_INPUT_DATA, "Chỉ nhân sự mới có số phòng làm việc");
            }
        }
        return userMapper.toResponse(saveAndFlush(user));
    }

    @Override
    @Transactional
    public UserResponse changeRole(UUID id, RoleCode newRole) {
        if (id.equals(SecurityUtils.getCurrentUserId())) {
            throw new AppException(ErrorCode.CANNOT_MODIFY_OWN_ACCOUNT);
        }
        User user = findById(id);
        List<RoleCode> oldRoles = UserMapper.rolesOf(user);
        if (oldRoles.contains(RoleCode.PET_OWNER) || newRole == RoleCode.PET_OWNER) {
            throw new AppException(ErrorCode.INVALID_INPUT_DATA, "Không đổi vai trò giữa chủ nuôi và nhân sự");
        }
        if (oldRoles.size() == 1 && oldRoles.get(0) == newRole) {
            throw new AppException(ErrorCode.INVALID_INPUT_DATA, "Tài khoản đã có vai trò này");
        }
        Role role = roleRepository.findByCode(newRole).orElseThrow(() -> new AppException(ErrorCode.ROLE_NOT_FOUND));
        user.getRoles().clear();
        user.getRoles().add(role);
        StaffProfile staff = user.getStaffProfile();
        if (staff != null) {
            staff.setPositionTitle(role.getName());
            staff.setDepartment(departmentOf(newRole));
            if (!ROOM_ROLES.contains(newRole)) {
                staff.setRoomNumber(null);   // vai trò mới không có phòng cố định
            }
        }
        saveAndFlush(user);
        // Access Token cũ vẫn mang vai trò cũ tối đa 30 phút; Refresh Token bị thu hồi nên phiên kế tiếp phải đăng nhập lại
        refreshTokenRepository.revokeAllUserTokens(user.getId());
        log.info("Đổi vai trò userId={}: {} -> {} bởi userId={}", id, oldRoles, newRole, SecurityUtils.getCurrentUserId());
        return userMapper.toResponse(user);
    }

    @Override
    @Transactional
    public void resetPassword(UUID id, String newPassword) {
        boolean admin = actsAsAdmin();
        if (id.equals(SecurityUtils.getCurrentUserId())) {
            throw new AppException(ErrorCode.CANNOT_MODIFY_OWN_ACCOUNT);
        }
        requireEncodablePassword(newPassword);
        User user = findById(id);
        // Lễ tân chỉ đặt lại mật khẩu cho chủ nuôi tại quầy, không đụng tới tài khoản nhân sự
        if (!admin && !UserMapper.rolesOf(user).equals(List.of(RoleCode.PET_OWNER))) {
            throw new AppException(ErrorCode.ACCESS_DENIED);
        }
        user.setPasswordHash(passwordEncoder.encode(newPassword));
        userRepository.save(user);
        refreshTokenRepository.revokeAllUserTokens(user.getId());
        log.info("Đặt lại mật khẩu userId={} bởi userId={}", id, SecurityUtils.getCurrentUserId());
    }

    @Override
    @Transactional
    public UserResponse changeStatus(UUID id, UserStatus status) {
        if (id.equals(SecurityUtils.getCurrentUserId())) {
            throw new AppException(ErrorCode.CANNOT_MODIFY_OWN_ACCOUNT);
        }
        User user = findById(id);
        user.setStatus(status);
        userRepository.save(user);
        if (status != UserStatus.ACTIVE) {
            // flushAutomatically ghi trạng thái mới trước; Access Token còn lại tự hết hạn trong tối đa 30 phút
            refreshTokenRepository.revokeAllUserTokens(user.getId());
        }
        log.info("Đổi trạng thái tài khoản userId={} -> {} bởi userId={}", id, status, SecurityUtils.getCurrentUserId());
        return userMapper.toResponse(user);
    }

    @Override
    @Transactional(readOnly = true)
    public List<UserSummaryResponse> getActiveDoctors() {
        return getDoctors(UserStatus.ACTIVE);
    }

    @Override
    @Transactional(readOnly = true)
    public List<UserSummaryResponse> getDoctors(UserStatus status) {
        return userRepository.findByRoleAndStatus(RoleCode.VETERINARIAN, status == null ? UserStatus.ACTIVE : status)
                .stream().map(userMapper::toSummary).toList();
    }

    // ------------------------------------------------------------------ nội bộ

    @Override
    @Transactional(readOnly = true)
    public UserSummaryResponse getSummary(UUID id) {
        return userMapper.toSummary(findById(id));
    }

    @Override
    @Transactional(readOnly = true)
    public List<UserSummaryResponse> getSummaries(List<UUID> ids) {
        if (ids == null || ids.isEmpty()) {
            return List.of();
        }
        if (ids.size() > MAX_BATCH_IDS) {
            throw new AppException(ErrorCode.INVALID_INPUT_DATA, "Tối đa " + MAX_BATCH_IDS + " id mỗi lần tra cứu");
        }
        return userRepository.findDetailedByIdIn(ids).stream().map(userMapper::toSummary).toList();
    }

    @Override
    @Transactional(readOnly = true)
    public long countActiveDoctors() {
        return userRepository.countByRoleAndStatus(RoleCode.VETERINARIAN, UserStatus.ACTIVE);
    }

    // ------------------------------------------------------------------ helper

    private User findById(UUID id) {
        return userRepository.findDetailedById(id)
                .orElseThrow(() -> new AppException(ErrorCode.USER_NOT_FOUND));
    }

    private User findCurrentUser() {
        return findById(SecurityUtils.getCurrentUserId());
    }

    /** Có vai trò nào đó (nếu không đăng nhập thì ném UNAUTHORIZED) và vai trò đó có ADMIN hay không. */
    private boolean actsAsAdmin() {
        SecurityUtils.getCurrentRole().orElseThrow(() -> new AppException(ErrorCode.UNAUTHORIZED));
        return SecurityUtils.hasRole(RoleCode.ADMIN);
    }

    /** Cập nhật các trường hồ sơ có gửi lên (null = giữ nguyên) và kiểm tra trùng SĐT / email. */
    private void applyProfile(User user, String fullName, String phoneNumber, String email, String address) {
        UserProfile profile = user.getProfile();
        if (fullName != null) {
            if (fullName.isBlank()) {
                throw new AppException(ErrorCode.INVALID_INPUT_DATA, "Họ tên không được để trống");
            }
            profile.setFullName(fullName.trim());
        }
        if (phoneNumber != null) {
            String phone = phoneNumber.trim();
            if (!phone.equals(profile.getPhoneNumber())) {
                if (userProfileRepository.existsByPhoneNumberAndUserIdNot(phone, user.getId())) {
                    throw new AppException(ErrorCode.PHONE_NUMBER_ALREADY_EXISTS);
                }
                profile.setPhoneNumber(phone);
            }
        }
        if (email != null) {
            String newEmail = blankToNull(email);
            if (newEmail != null && !newEmail.equalsIgnoreCase(profile.getEmail())
                    && userProfileRepository.existsByEmailIgnoreCaseAndUserIdNot(newEmail, user.getId())) {
                throw new AppException(ErrorCode.INVALID_INPUT_DATA, "Email đã được sử dụng cho tài khoản khác");
            }
            profile.setEmail(newEmail);
        }
        if (address != null) {
            profile.setAddress(blankToNull(address));
        }
    }

    /** Trả về số phòng viết hoa, hoặc null nếu để trống. Chỉ Bác sĩ / KTV mới được có số phòng. */
    private static String normalizeRoom(String roomNumber, Set<RoleCode> roles) {
        if (!hasText(roomNumber)) {
            return null;
        }
        if (roles.stream().noneMatch(ROOM_ROLES::contains)) {
            throw new AppException(ErrorCode.INVALID_INPUT_DATA, "Chỉ Bác sĩ / Kỹ thuật viên mới có số phòng làm việc");
        }
        return roomNumber.trim().toUpperCase(Locale.ROOT);
    }

    /** Bộ phận mặc định ghi vào hồ sơ nhân sự khi tạo / đổi vai trò. */
    private static String departmentOf(RoleCode role) {
        return switch (role) {
            case VETERINARIAN -> "Khám bệnh";
            case RECEPTIONIST -> "Tiếp đón";
            case LAB_TECHNICIAN -> "Xét nghiệm";
            case IMAGING_TECHNICIAN -> "Chẩn đoán hình ảnh";
            case ADMIN -> "Ban quản lý";
            case PET_OWNER -> null;
        };
    }

    private static void requireEncodablePassword(String rawPassword) {
        if (AccountRules.exceedsBcryptLimit(rawPassword)) {
            throw new AppException(ErrorCode.INVALID_INPUT_DATA, "Mật khẩu tối đa 72 byte");
        }
    }

    private User saveAndFlush(User user) {
        try {
            return userRepository.saveAndFlush(user);
        } catch (DataIntegrityViolationException e) {
            // Hai request đồng thời cùng qua bước kiểm tra trùng; ràng buộc UNIQUE của DB là chốt chặn cuối
            throw new AppException(ErrorCode.USERNAME_ALREADY_EXISTS, "Tên đăng nhập, số điện thoại hoặc email đã tồn tại");
        }
    }

    private static boolean hasText(String value) {
        return value != null && !value.isBlank();
    }

    private static String blankToNull(String value) {
        return hasText(value) ? value.trim() : null;
    }
}
