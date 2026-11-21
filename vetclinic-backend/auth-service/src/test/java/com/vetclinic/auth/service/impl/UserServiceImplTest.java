package com.vetclinic.auth.service.impl;

import com.vetclinic.auth.constant.UserStatus;
import com.vetclinic.auth.dto.request.CreateUserRequest;
import com.vetclinic.auth.dto.request.RegisterRequest;
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
import com.vetclinic.common.exception.AppException;
import com.vetclinic.common.exception.ErrorCode;
import com.vetclinic.common.security.RoleCode;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;

import java.time.LocalDate;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.LongStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class UserServiceImplTest {

    private static final UUID ADMIN = uid(1);
    private static final UUID RECEPTIONIST = uid(2);

    @Mock
    private UserRepository userRepository;
    @Mock
    private RoleRepository roleRepository;
    @Mock
    private UserProfileRepository userProfileRepository;
    @Mock
    private StaffProfileRepository staffProfileRepository;
    @Mock
    private PetOwnerProfileRepository petOwnerProfileRepository;
    @Mock
    private RefreshTokenRepository refreshTokenRepository;

    private final PasswordEncoder encoder = new BCryptPasswordEncoder(4);
    private UserServiceImpl service;

    @BeforeEach
    void setUp() {
        service = new UserServiceImpl(userRepository, roleRepository, userProfileRepository, staffProfileRepository,
                petOwnerProfileRepository, refreshTokenRepository, encoder, new UserMapper());
        lenient().when(userRepository.saveAndFlush(any(User.class))).thenAnswer(inv -> {
            User saved = inv.getArgument(0);
            if (saved.getId() == null) {
                saved.setId(uid(50));
            }
            return saved;
        });
        for (RoleCode code : RoleCode.values()) {
            lenient().when(roleRepository.findByCode(code)).thenReturn(Optional.of(role(code)));
        }
        lenient().when(staffProfileRepository.nextEmployeeNumber()).thenReturn(8L);
        lenient().when(petOwnerProfileRepository.nextCustomerNumber()).thenReturn(7L);
    }

    @AfterEach
    void clearSecurity() {
        SecurityContextHolder.clearContext();
    }

    /** UUID demo cố định, cùng dạng với dữ liệu seed (019a9442-d810-7000-8000-0000000000NN). */
    private static UUID uid(long n) {
        return UUID.fromString(String.format("019a9442-d810-7000-8000-%012d", n));
    }

    private static Role role(RoleCode code) {
        return Role.builder().id((short) (code.ordinal() + 1)).code(code).name(code.name()).build();
    }

    private static User user(UUID id, RoleCode code, String room) {
        User user = User.builder().id(id).username("u" + id).passwordHash("old-hash").status(UserStatus.ACTIVE).build();
        user.getRoles().add(role(code));
        user.setProfile(UserProfile.builder().user(user).fullName("Người " + id).phoneNumber("09000000" + id.getLeastSignificantBits()).build());
        if (code == RoleCode.PET_OWNER) {
            user.setPetOwnerProfile(PetOwnerProfile.builder().user(user).customerCode("KH0001").build());
        } else {
            user.setStaffProfile(StaffProfile.builder().user(user).employeeCode("NV0001").positionTitle(code.name())
                    .roomNumber(room).hireDate(LocalDate.of(2026, 1, 2)).build());
        }
        return user;
    }

    private static void loginAs(UUID userId, RoleCode... roles) {
        List<SimpleGrantedAuthority> authorities = Arrays.stream(roles).map(r -> new SimpleGrantedAuthority("ROLE_" + r.name())).toList();
        SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(userId.toString(), null, authorities));
    }

    private static void assertError(ErrorCode expected, Runnable call) {
        assertThatThrownBy(call::run).isInstanceOfSatisfying(AppException.class,
                e -> assertThat(e.getErrorCode()).isEqualTo(expected));
    }

    private User savedUser() {
        ArgumentCaptor<User> saved = ArgumentCaptor.forClass(User.class);
        verify(userRepository).saveAndFlush(saved.capture());
        return saved.getValue();
    }

    // ================================================================== tự đăng ký

    @Test
    void register_alwaysCreatesAnActivePetOwnerWithThePhoneAsUsername() {
        UserResponse response = service.register(new RegisterRequest("Chunuoi@1", "  Nguyễn Văn A ", "0944444441", "a@example.com", null));

        User user = savedUser();
        assertThat(user.getRoles()).extracting(Role::getCode).containsExactly(RoleCode.PET_OWNER);   // không có đường nào để tự cấp quyền khác
        assertThat(user.getStatus()).isEqualTo(UserStatus.ACTIVE);
        assertThat(user.getUsername()).isEqualTo("0944444441");
        assertThat(user.getProfile().getFullName()).isEqualTo("Nguyễn Văn A");
        assertThat(user.getProfile().getPhoneNumber()).isEqualTo("0944444441");
        assertThat(user.getProfile().getEmail()).isEqualTo("a@example.com");
        assertThat(user.getStaffProfile()).isNull();
        assertThat(user.getPetOwnerProfile().getCustomerCode()).isEqualTo("KH0007");
        assertThat(user.getPetOwnerProfile().getRegisteredChannel()).isEqualTo("ONLINE");
        assertThat(encoder.matches("Chunuoi@1", user.getPasswordHash())).isTrue();   // lưu BCrypt, không lưu mật khẩu thô
        assertThat(response.role()).isEqualTo(RoleCode.PET_OWNER);
        assertThat(response.roles()).containsExactly(RoleCode.PET_OWNER);
        assertThat(response.roomNumber()).isNull();
    }

    @Test
    void register_refusesAPhoneNumberThatIsAlreadyRegistered() {
        when(userProfileRepository.existsByPhoneNumber("0944444441")).thenReturn(true);

        assertError(ErrorCode.PHONE_NUMBER_ALREADY_EXISTS,
                () -> service.register(new RegisterRequest("Chunuoi@1", "A", "0944444441", null, null)));
        verify(userRepository, never()).saveAndFlush(any());
    }

    @Test
    void register_refusesAnEmailThatBelongsToAnotherAccount() {
        when(userProfileRepository.existsByEmailIgnoreCase("a@example.com")).thenReturn(true);

        assertError(ErrorCode.INVALID_INPUT_DATA,
                () -> service.register(new RegisterRequest("Chunuoi@1", "A", "0944444441", "a@example.com", null)));
        verify(userRepository, never()).saveAndFlush(any());
    }

    @Test
    void register_refusesAPasswordBcryptWouldReject() {
        String tooLong = "Aa1" + "x".repeat(80);

        assertError(ErrorCode.INVALID_INPUT_DATA, () -> service.register(new RegisterRequest(tooLong, "A", "0944444441", null, null)));
    }

    // ================================================================== tạo tài khoản

    @Test
    void create_aDoctorByAdminWritesUserRoleProfileAndStaffProfileWithTheRoom() {
        loginAs(ADMIN, RoleCode.ADMIN);

        UserResponse response = service.create(new CreateUserRequest(null, "Doctor@123", "BS Mới", "0955555551", null, null, "p05",
                RoleCode.VETERINARIAN));

        User user = savedUser();
        assertThat(user.getRoles()).extracting(Role::getCode).containsExactly(RoleCode.VETERINARIAN);
        assertThat(user.getProfile().getFullName()).isEqualTo("BS Mới");
        assertThat(user.getPetOwnerProfile()).isNull();
        StaffProfile staff = user.getStaffProfile();
        assertThat(staff.getEmployeeCode()).isEqualTo("NV0008");
        assertThat(staff.getPositionTitle()).isEqualTo("VETERINARIAN");
        assertThat(staff.getDepartment()).isEqualTo("Khám bệnh");
        assertThat(staff.getRoomNumber()).isEqualTo("P05");   // viết hoa
        assertThat(staff.getHireDate()).isNotNull();
        assertThat(response.roomNumber()).isEqualTo("P05");
    }

    @Test
    void create_refusesARoomAlreadyAssignedToAnotherStaffMember() {
        loginAs(ADMIN, RoleCode.ADMIN);
        when(staffProfileRepository.existsByRoomNumber("P05")).thenReturn(true);

        assertError(ErrorCode.ROOM_NUMBER_ALREADY_ASSIGNED,
                () -> service.create(new CreateUserRequest(null, "Doctor@123", "BS Mới", "0955555551", null, null, "P05", RoleCode.VETERINARIAN)));
        verify(userRepository, never()).saveAndFlush(any());
    }

    @Test
    void create_aReceptionistMayOnlyCreatePetOwners_markedAsCreatedAtTheClinic() {
        loginAs(RECEPTIONIST, RoleCode.RECEPTIONIST);

        assertError(ErrorCode.ACCESS_DENIED,
                () -> service.create(new CreateUserRequest(null, "Doctor@123", "BS", "0955555551", null, null, null, RoleCode.VETERINARIAN)));

        service.create(new CreateUserRequest(null, "Owner@1234", "Khách quầy", "0955555552", null, null, null, RoleCode.PET_OWNER));
        assertThat(savedUser().getPetOwnerProfile().getRegisteredChannel()).isEqualTo("AT_CLINIC");
    }

    @Test
    void create_onlyDoctorsAndTechniciansMayHaveARoom() {
        loginAs(ADMIN, RoleCode.ADMIN);

        assertError(ErrorCode.INVALID_INPUT_DATA,
                () -> service.create(new CreateUserRequest(null, "Staff@1234", "Lễ tân", "0955555553", null, null, "P01", RoleCode.RECEPTIONIST)));
    }

    // ================================================================== đổi vai trò

    @Test
    void changeRole_movesStaffToTheNewRoleAndRevokesTheirSessions() {
        loginAs(ADMIN, RoleCode.ADMIN);
        User receptionist = user(uid(7), RoleCode.RECEPTIONIST, null);
        when(userRepository.findDetailedById(uid(7))).thenReturn(Optional.of(receptionist));

        UserResponse response = service.changeRole(uid(7), RoleCode.VETERINARIAN);

        assertThat(receptionist.getRoles()).extracting(Role::getCode).containsExactly(RoleCode.VETERINARIAN);
        assertThat(receptionist.getStaffProfile().getDepartment()).isEqualTo("Khám bệnh");
        assertThat(response.role()).isEqualTo(RoleCode.VETERINARIAN);
        verify(refreshTokenRepository).revokeAllUserTokens(uid(7));   // phiên kế tiếp phải mang vai trò mới
    }

    @Test
    void changeRole_awayFromAClinicRoleClearsTheFixedRoomNumber() {
        loginAs(ADMIN, RoleCode.ADMIN);
        User doctor = user(uid(7), RoleCode.VETERINARIAN, "P01");
        when(userRepository.findDetailedById(uid(7))).thenReturn(Optional.of(doctor));

        service.changeRole(uid(7), RoleCode.RECEPTIONIST);

        assertThat(doctor.getStaffProfile().getRoomNumber()).isNull();
    }

    @Test
    void changeRole_betweenRoomRolesKeepsTheRoom() {
        loginAs(ADMIN, RoleCode.ADMIN);
        User tech = user(uid(7), RoleCode.LAB_TECHNICIAN, "LAB01");
        when(userRepository.findDetailedById(uid(7))).thenReturn(Optional.of(tech));

        service.changeRole(uid(7), RoleCode.IMAGING_TECHNICIAN);

        assertThat(tech.getStaffProfile().getRoomNumber()).isEqualTo("LAB01");
    }

    @Test
    void changeRole_refusesSelfSameRoleAndAnythingInvolvingPetOwners() {
        loginAs(ADMIN, RoleCode.ADMIN);
        assertError(ErrorCode.CANNOT_MODIFY_OWN_ACCOUNT, () -> service.changeRole(ADMIN, RoleCode.VETERINARIAN));

        when(userRepository.findDetailedById(uid(7))).thenReturn(Optional.of(user(uid(7), RoleCode.RECEPTIONIST, null)));
        assertError(ErrorCode.INVALID_INPUT_DATA, () -> service.changeRole(uid(7), RoleCode.RECEPTIONIST));
        assertError(ErrorCode.INVALID_INPUT_DATA, () -> service.changeRole(uid(7), RoleCode.PET_OWNER));

        when(userRepository.findDetailedById(uid(8))).thenReturn(Optional.of(user(uid(8), RoleCode.PET_OWNER, null)));
        assertError(ErrorCode.INVALID_INPUT_DATA, () -> service.changeRole(uid(8), RoleCode.VETERINARIAN));
        verify(refreshTokenRepository, never()).revokeAllUserTokens(any());
    }

    // ================================================================== đặt lại mật khẩu

    @Test
    void resetPassword_byAdminReplacesTheHashAndRevokesSessions() {
        loginAs(ADMIN, RoleCode.ADMIN);
        User doctor = user(uid(7), RoleCode.VETERINARIAN, "P01");
        when(userRepository.findDetailedById(uid(7))).thenReturn(Optional.of(doctor));

        service.resetPassword(uid(7), "MatKhau@2026");

        assertThat(encoder.matches("MatKhau@2026", doctor.getPasswordHash())).isTrue();
        verify(userRepository).save(doctor);
        verify(refreshTokenRepository).revokeAllUserTokens(uid(7));
    }

    @Test
    void resetPassword_byReceptionistOnlyForPetOwners() {
        loginAs(RECEPTIONIST, RoleCode.RECEPTIONIST);
        User owner = user(uid(8), RoleCode.PET_OWNER, null);
        User doctor = user(uid(7), RoleCode.VETERINARIAN, "P01");
        when(userRepository.findDetailedById(uid(8))).thenReturn(Optional.of(owner));
        when(userRepository.findDetailedById(uid(7))).thenReturn(Optional.of(doctor));

        service.resetPassword(uid(8), "MatKhau@2026");
        assertThat(encoder.matches("MatKhau@2026", owner.getPasswordHash())).isTrue();

        assertError(ErrorCode.ACCESS_DENIED, () -> service.resetPassword(uid(7), "MatKhau@2026"));
        assertThat(doctor.getPasswordHash()).isEqualTo("old-hash");   // tài khoản nhân sự không bị đụng tới
    }

    @Test
    void resetPassword_aReceptionistWhoIsAlsoAdminMayResetStaff() {
        loginAs(RECEPTIONIST, RoleCode.RECEPTIONIST, RoleCode.ADMIN);
        User doctor = user(uid(7), RoleCode.VETERINARIAN, "P01");
        when(userRepository.findDetailedById(uid(7))).thenReturn(Optional.of(doctor));

        service.resetPassword(uid(7), "MatKhau@2026");

        assertThat(encoder.matches("MatKhau@2026", doctor.getPasswordHash())).isTrue();
    }

    @Test
    void resetPassword_notForYourOwnAccountAndNotForUnknownOrOverlongPasswords() {
        loginAs(ADMIN, RoleCode.ADMIN);
        assertError(ErrorCode.CANNOT_MODIFY_OWN_ACCOUNT, () -> service.resetPassword(ADMIN, "MatKhau@2026"));
        assertError(ErrorCode.INVALID_INPUT_DATA, () -> service.resetPassword(uid(7), "Aa1" + "x".repeat(80)));
        when(userRepository.findDetailedById(uid(99))).thenReturn(Optional.empty());
        assertError(ErrorCode.USER_NOT_FOUND, () -> service.resetPassword(uid(99), "MatKhau@2026"));
        verify(refreshTokenRepository, never()).revokeAllUserTokens(any());
    }

    // ================================================================== tra cứu nội bộ

    @Test
    void getDoctors_returnsSummariesWithEveryRoleAndTheRoomFromTheStaffProfile() {
        User doctor = user(uid(7), RoleCode.VETERINARIAN, "P01");
        doctor.getRoles().add(role(RoleCode.ADMIN));   // Bác sĩ kiêm Quản trị viên vẫn là Bác sĩ
        when(userRepository.findByRoleAndStatus(RoleCode.VETERINARIAN, UserStatus.ACTIVE)).thenReturn(List.of(doctor));

        List<UserSummaryResponse> doctors = service.getActiveDoctors();

        assertThat(doctors).hasSize(1);
        assertThat(doctors.get(0).id()).isEqualTo(uid(7));
        assertThat(doctors.get(0).roomNumber()).isEqualTo("P01");
        assertThat(doctors.get(0).roles()).containsExactly(RoleCode.ADMIN, RoleCode.VETERINARIAN);
        assertThat(doctors.get(0).status()).isEqualTo(UserStatus.ACTIVE);
    }

    @Test
    void getSummaries_isBoundedToAvoidHugeBatches() {
        List<UUID> tooMany = LongStream.rangeClosed(1, 201).mapToObj(UserServiceImplTest::uid).toList();

        assertError(ErrorCode.INVALID_INPUT_DATA, () -> service.getSummaries(tooMany));
        assertThat(service.getSummaries(List.of())).isEmpty();
    }
}
