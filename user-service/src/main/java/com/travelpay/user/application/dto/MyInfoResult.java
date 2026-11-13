package com.travelpay.user.application.dto;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import com.travelpay.common.auth.AuthRole;
import com.travelpay.user.domain.model.vo.LoginMethod;
import com.travelpay.user.domain.model.vo.UserStatus;
import com.travelpay.user.domain.repository.UserProfile;
import org.jspecify.annotations.Nullable;

/**
 * 마이페이지에 보여 줄 본인 정보입니다. 자체 로그인 수단이 없으면 loginEmail은 null입니다.
 * 개인정보를 담으므로 문자열 표현에는 값을 넣지 않습니다.
 */
public record MyInfoResult(
        UUID userId,
        String realName,
        String phone,
        @Nullable String loginEmail,
        UserStatus status,
        AuthRole role,
        List<LoginMethod> loginMethods,
        Instant createdAt
) {

    public MyInfoResult {
        loginMethods = List.copyOf(loginMethods);
    }

    // 로그인 이메일은 비밀번호 로그인 정보가 있을 때만 있으므로(열이 NOT NULL), 이메일이 있으면 비밀번호 로그인 수단이 있음
    public static MyInfoResult from(UserProfile profile) {
        return new MyInfoResult(profile.userId(), profile.realName(), profile.phone(), profile.loginEmail(),
                profile.status(), profile.role(),
                profile.loginEmail() == null ? List.of() : List.of(LoginMethod.PASSWORD), profile.createdAt());
    }

    @Override
    public String toString() {
        return "MyInfoResult[REDACTED]";
    }
}
