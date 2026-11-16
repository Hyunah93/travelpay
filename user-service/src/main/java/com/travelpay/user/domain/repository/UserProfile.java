package com.travelpay.user.domain.repository;

import java.time.Instant;
import java.util.UUID;

import com.travelpay.common.auth.AuthRole;
import com.travelpay.user.domain.model.vo.UserStatus;
import org.jspecify.annotations.Nullable;

/** 마이페이지에 보여 줄 회원 정보입니다. 비밀번호 로그인 정보가 없는 회원은 loginEmail이 null입니다. */
public record UserProfile(UUID userId, String realName, String phone, @Nullable String loginEmail, UserStatus status,
        AuthRole role, Instant createdAt) {

    // 개인정보를 담으므로 문자열 표현(로그 등)에는 값을 넣지 않음
    @Override
    public String toString() {
        return "UserProfile[REDACTED]";
    }
}
