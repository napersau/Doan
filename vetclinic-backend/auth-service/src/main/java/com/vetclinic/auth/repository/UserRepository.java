package com.vetclinic.auth.repository;

import com.vetclinic.auth.constant.UserStatus;
import com.vetclinic.auth.entity.User;
import com.vetclinic.common.security.RoleCode;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface UserRepository extends JpaRepository<User, UUID>, JpaSpecificationExecutor<User> {

    // Luôn nạp kèm vai trò và hồ sơ: open-in-view đã tắt nên không thể lazy-load sau khi transaction kết thúc
    @EntityGraph(attributePaths = {"roles", "profile", "staffProfile"})
    Optional<User> findByUsername(String username);

    @EntityGraph(attributePaths = {"roles", "profile", "staffProfile"})
    @Query("select u from User u where u.id = :id")
    Optional<User> findDetailedById(@Param("id") UUID id);

    @EntityGraph(attributePaths = {"roles", "profile", "staffProfile"})
    @Query("select u from User u where u.id in :ids")
    List<User> findDetailedByIdIn(@Param("ids") Collection<UUID> ids);

    /** Mỗi người chỉ khớp một dòng user_roles cho một mã vai trò nên không cần DISTINCT. */
    @EntityGraph(attributePaths = {"roles", "profile", "staffProfile"})
    @Query("select u from User u join u.roles r where r.code = :code and u.status = :status order by u.profile.fullName asc")
    List<User> findByRoleAndStatus(@Param("code") RoleCode code, @Param("status") UserStatus status);

    @Query("select count(u) from User u join u.roles r where r.code = :code and u.status = :status")
    long countByRoleAndStatus(@Param("code") RoleCode code, @Param("status") UserStatus status);

    // Trang kết quả chỉ nạp kèm hồ sơ (1-1); vai trò nạp lười theo lô trong transaction của service (tránh phân trang trong bộ nhớ)
    @Override
    @EntityGraph(attributePaths = {"profile", "staffProfile"})
    Page<User> findAll(Specification<User> spec, Pageable pageable);

    boolean existsByUsername(String username);
}
