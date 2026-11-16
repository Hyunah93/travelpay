package com.travelpay.ledger.domain.integrity;

import java.util.List;

/**
 * 검사 하나의 결과. 세 값 모두 같은 SQL 문장에서 집계한다.
 *
 * @param checkedCount   실제로 검사한 계정 또는 거래 수
 * @param violationCount 전체 위반 건수
 * @param violations     정렬 후 앞쪽부터 최대 violationLimit건
 */
public record IntegrityCheck<T>(long checkedCount, long violationCount, List<T> violations) {

    public IntegrityCheck {
        violations = List.copyOf(violations);
    }
}
