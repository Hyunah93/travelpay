package com.travelpay.ledger.application.transaction;

import com.travelpay.common.error.BusinessException;
import com.travelpay.ledger.application.transaction.dto.CancelTransactionCommand;
import com.travelpay.ledger.application.transaction.dto.CancelTransactionResult;
import com.travelpay.ledger.application.transaction.dto.ChargeReversalCommand;
import com.travelpay.ledger.application.transaction.dto.ChargeReversalResult;
import com.travelpay.ledger.application.transaction.dto.LedgerEntryResult;
import com.travelpay.ledger.application.transaction.dto.RegisterTransactionCommand;
import com.travelpay.ledger.application.transaction.dto.TransactionDetailResult;
import com.travelpay.ledger.application.transaction.dto.TransactionResult;
import com.travelpay.ledger.domain.Currency;
import com.travelpay.ledger.domain.LedgerErrorCode;
import com.travelpay.ledger.domain.account.AccountType;
import com.travelpay.ledger.domain.account.LedgerAccount;
import com.travelpay.ledger.domain.account.LedgerAccountRepository;
import com.travelpay.ledger.domain.account.OwnerType;
import com.travelpay.ledger.domain.transaction.Direction;
import com.travelpay.ledger.domain.transaction.EntryType;
import com.travelpay.ledger.domain.transaction.LedgerEntry;
import com.travelpay.ledger.domain.transaction.LedgerEntryLine;
import com.travelpay.ledger.domain.transaction.LedgerTransaction;
import com.travelpay.ledger.domain.transaction.LedgerTransactionRepository;
import com.travelpay.ledger.domain.transaction.ReferenceType;
import com.travelpay.ledger.domain.transaction.RejectReason;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * 거래 한 건을 한 트랜잭션으로 기록한다. 거래·항목·계정 잔액이 함께 커밋되거나 함께 롤백된다.
 *
 * READ COMMITTED 전제 (spring.datasource.hikari.transaction-isolation): FOR UPDATE는 다른 트랜잭션이 커밋한 뒤의
 * 최신 행을 잠그므로 잠금 후에 읽은 상태·잔액으로 판단하면 정확하다. REPEATABLE READ로 올리면 그 사이 바뀐 행을 잠글 때
 * 직렬화 오류로 롤백된다. 격리 수준은 @Transactional에 적지 않는다(적으면 트랜잭션마다 격리 수준 조회가 나간다).
 *
 * 같은 reference 동시 요청은 여기서 처리하지 않는다. 거래 INSERT의 UNIQUE 위반(DuplicateReferenceException)으로
 * 이 트랜잭션은 롤백되고, LedgerTransactionService가 새 트랜잭션에서 기존 거래를 다시 읽는다.
 */
@Component
@RequiredArgsConstructor
class LedgerTransactionRecorder {

    /** DB가 느릴 때 호출자를 붙잡아 두는 상한(초) */
    private static final int TX_TIMEOUT_SECONDS = 3;

    private final LedgerTransactionRepository transactionRepository;
    private final LedgerAccountRepository accountRepository;

    @Transactional(timeout = TX_TIMEOUT_SECONDS)
    public TransactionResult record(RegisterTransactionCommand command, String requestHash) {
        Optional<LedgerTransaction> existing =
                transactionRepository.findByReference(command.referenceType(), command.referenceId());
        if (existing.isPresent()) {
            return toExistingResult(existing.get(), requestHash);
        }

        if (command.originalTransactionId() != null
                && !transactionRepository.existsById(command.originalTransactionId())) {
            throw new BusinessException(LedgerErrorCode.LEDGER_ORIGINAL_TRANSACTION_NOT_FOUND);
        }
        // TODO: referenceType별 추가 검증(예: 결제 환불의 원거래 상태·중복·금액)은 유형별 정책 클래스로 해당 도메인 담당이 추가 예정

        // 잠금 전에는 ID만 찾는다. 엔티티를 먼저 읽어 두면 아래 FOR UPDATE 조회가 이미 올라온 인스턴스를
        // 그대로 돌려줘 잠금 전에 읽은 잔액으로 판단하게 된다.
        List<UUID> accountIds = new ArrayList<>(command.entries().size());
        for (RegisterTransactionCommand.Entry entry : command.entries()) {
            Optional<UUID> accountId = findAccountId(entry);
            if (accountId.isEmpty()) {
                return reject(command, requestHash, RejectReason.ACCOUNT_NOT_FOUND);
            }
            accountIds.add(accountId.get());
        }

        // 데드락 방지: 모든 거래가 계정 ID 순으로 잠근다.
        // 핫 계정: CHARGE_SOURCE는 음수 허용이라 잔액 확인이 필요 없지만 잠금 순서 일관성을 위해 같은 규칙으로 잠근다.
        // 모든 충전이 몰리는 계정이라 성능 측정 후 개선 대상.
        Set<UUID> distinctIds = new LinkedHashSet<>(accountIds);
        Map<UUID, LedgerAccount> accounts = accountRepository.lockAllInIdOrder(distinctIds)
                .stream()
                .collect(Collectors.toMap(LedgerAccount::getId, Function.identity()));
        if (accounts.size() != distinctIds.size()) {
            return reject(command, requestHash, RejectReason.ACCOUNT_NOT_FOUND);
        }

        List<ResolvedEntry> resolvedEntries = inApplyOrder(command, accountIds);
        Map<UUID, Long> netChanges = netChanges(resolvedEntries);
        Optional<RejectReason> rejectReason = judge(accounts, netChanges);
        if (rejectReason.isPresent()) {
            return reject(command, requestHash, rejectReason.get());
        }

        LedgerTransaction transaction = post(command, requestHash, accounts, resolvedEntries, netChanges);
        return TransactionResult.of(transaction, true);
    }

    /** 동시 요청으로 먼저 저장된 거래를 새 트랜잭션에서 다시 읽어 같은 규칙으로 응답한다 */
    @Transactional(readOnly = true, timeout = TX_TIMEOUT_SECONDS)
    public TransactionResult resolveExisting(RegisterTransactionCommand command, String requestHash) {
        LedgerTransaction existing = transactionRepository
                .findByReference(command.referenceType(), command.referenceId())
                .orElseThrow(() -> new IllegalStateException("중복으로 거절된 거래를 다시 찾을 수 없습니다."));
        return toExistingResult(existing, requestHash);
    }

    /**
     * 취소 기록. 같은 reference의 거래가 이미 있으면 내용과 상관없이 바꾸지 않고 그대로 돌려준다(요청 해시를 비교하지 않음).
     * 계정을 잠그지 않고 거래 행 하나만 저장한다. 등록과 동시에 저장되면 reference UNIQUE 위반(DuplicateReferenceException)으로 롤백된다.
     */
    @Transactional(timeout = TX_TIMEOUT_SECONDS)
    public CancelTransactionResult cancel(CancelTransactionCommand command, String requestHash) {
        Optional<LedgerTransaction> existing =
                transactionRepository.findByReference(command.referenceType(), command.referenceId());
        if (existing.isPresent()) {
            return new CancelTransactionResult(detail(existing.get()), false);
        }
        LedgerTransaction cancelled = transactionRepository.save(LedgerTransaction.rejected(
                command.referenceType(), command.referenceId(), requestHash, command.userId(),
                RejectReason.CANCELLED_BY_REQUESTER, null, command.description()));
        return new CancelTransactionResult(TransactionDetailResult.of(cancelled, List.of()), true);
    }

    /** 취소와 동시에 먼저 저장된 거래를 새 트랜잭션에서 다시 읽어 그대로 돌려준다 */
    @Transactional(readOnly = true, timeout = TX_TIMEOUT_SECONDS)
    public CancelTransactionResult existingForCancel(ReferenceType referenceType, UUID referenceId) {
        LedgerTransaction existing = transactionRepository.findByReference(referenceType, referenceId)
                .orElseThrow(() -> new IllegalStateException("중복으로 거절된 취소의 기존 거래를 다시 찾을 수 없습니다."));
        return new CancelTransactionResult(detail(existing), false);
    }

    /**
     * 충전 취소 분개. 이중 지급된 충전 (CHARGE, chargeId)를 원장에서 되돌린다.
     * 회원 지갑은 잠근 뒤의 잔액만큼 회수하고(DEBIT CHARGE_REVERSAL_OUT), 모자란 만큼은 미수 계정에 남기며
     * (DEBIT CHARGE_REVERSAL_RECEIVABLE), 충전 원천에는 원금액을 되돌린다(CREDIT CHARGE_REVERSAL_IN).
     * 금액이 0인 항목은 만들지 않는다. 거절(원거래 없음·구조 다름·계정 사용 불가)은 기록하지 않고 예외로 롤백한다.
     * 같은 chargeId 동시 요청은 거래 INSERT의 UNIQUE 위반(DuplicateReferenceException)으로 롤백되고,
     * LedgerTransactionService가 새 트랜잭션에서 먼저 저장된 취소 분개를 읽는다.
     */
    @Transactional(timeout = TX_TIMEOUT_SECONDS)
    public ChargeReversalResult reverseCharge(ChargeReversalCommand command, String requestHash) {
        Optional<LedgerTransaction> existing =
                transactionRepository.findByReference(ReferenceType.CHARGE_REVERSAL, command.chargeId());
        if (existing.isPresent()) {
            return ChargeReversalResult.of(detail(existing.get()), false);
        }

        LedgerTransaction original = transactionRepository.findByReference(ReferenceType.CHARGE, command.chargeId())
                .orElseThrow(() -> new BusinessException(LedgerErrorCode.LEDGER_TRANSACTION_NOT_FOUND));
        if (!original.isPosted()) {
            throw new BusinessException(LedgerErrorCode.LEDGER_ORIGINAL_NOT_REVERSIBLE);
        }
        LedgerEntryLine walletLine = chargeWalletLine(transactionRepository.findEntryLines(original.getId()));
        Currency currency = walletLine.currency();
        long amount = walletLine.amount();

        // 잠금 전에는 ID만 찾는다 (record와 같은 이유). 원거래가 POSTED라 지갑·충전 원천 계정은 있고, 미수 계정은 V8 시드
        UUID walletAccountId = accountRepository.findUserWalletAccountId(walletLine.walletId(), currency)
                .orElseThrow(() -> new IllegalStateException("원거래의 지갑 계정을 찾을 수 없습니다."));
        UUID sourceAccountId = accountRepository.findSystemAccountId(AccountType.CHARGE_SOURCE, currency)
                .orElseThrow(() -> new IllegalStateException("충전 원천 계정을 찾을 수 없습니다."));
        UUID receivableAccountId = accountRepository.findSystemAccountId(AccountType.RECEIVABLE, currency)
                .orElseThrow(() -> new IllegalStateException("미수 계정을 찾을 수 없습니다."));

        // 데드락 방지: record와 같이 계정 ID 순으로 한 번에 잠근다. 회수액이 0이어도 잔액을 읽으려고 지갑을 잠근다
        Set<UUID> distinctIds = new LinkedHashSet<>(List.of(walletAccountId, sourceAccountId, receivableAccountId));
        Map<UUID, LedgerAccount> accounts = accountRepository.lockAllInIdOrder(distinctIds)
                .stream()
                .collect(Collectors.toMap(LedgerAccount::getId, Function.identity()));
        if (accounts.size() != distinctIds.size()) {
            throw new IllegalStateException("잠근 계정 수가 다릅니다: expected=" + distinctIds.size()
                    + " actual=" + accounts.size());
        }
        if (accounts.values().stream().anyMatch(account -> !account.isActive())) {
            throw new BusinessException(LedgerErrorCode.LEDGER_REJECTED_WALLET_SUSPENDED);
        }

        // 잠근 뒤의 지갑 잔액으로 회수액을 정한다. USER 잔액은 음수가 아니므로 회수액은 0 이상 원금액 이하
        long recovered = Math.min(accounts.get(walletAccountId).getBalance(), amount);
        long shortfall = amount - recovered;
        List<RegisterTransactionCommand.Entry> entries = new ArrayList<>(3);
        List<UUID> accountIds = new ArrayList<>(3);
        if (recovered > 0) {
            entries.add(new RegisterTransactionCommand.Entry(OwnerType.USER, walletLine.walletId(), null, currency,
                    Direction.DEBIT, recovered, EntryType.CHARGE_REVERSAL_OUT.name()));
            accountIds.add(walletAccountId);
        }
        if (shortfall > 0) {
            entries.add(new RegisterTransactionCommand.Entry(OwnerType.SYSTEM, null, AccountType.RECEIVABLE, currency,
                    Direction.DEBIT, shortfall, EntryType.CHARGE_REVERSAL_RECEIVABLE.name()));
            accountIds.add(receivableAccountId);
        }
        entries.add(new RegisterTransactionCommand.Entry(OwnerType.SYSTEM, null, AccountType.CHARGE_SOURCE, currency,
                Direction.CREDIT, amount, EntryType.CHARGE_REVERSAL_IN.name()));
        accountIds.add(sourceAccountId);

        RegisterTransactionCommand reversal = new RegisterTransactionCommand(ReferenceType.CHARGE_REVERSAL,
                command.chargeId(), original.getUserId(), command.description(), original.getId(), entries);
        List<ResolvedEntry> resolvedEntries = inApplyOrder(reversal, accountIds);
        LedgerTransaction transaction = post(reversal, requestHash, accounts, resolvedEntries,
                netChanges(resolvedEntries));
        // 항목 조회(JPQL) 전에 저장 대기 중인 항목이 반영되므로, 이미 있던 경우와 같은 형식·순서로 돌려준다
        return ChargeReversalResult.of(detail(transaction), true);
    }

    /** 취소 분개와 동시에 먼저 저장된 취소 분개를 새 트랜잭션에서 다시 읽어 그대로 돌려준다 */
    @Transactional(readOnly = true, timeout = TX_TIMEOUT_SECONDS)
    public ChargeReversalResult existingChargeReversal(UUID chargeId) {
        LedgerTransaction existing = transactionRepository.findByReference(ReferenceType.CHARGE_REVERSAL, chargeId)
                .orElseThrow(() -> new IllegalStateException("중복으로 거절된 취소 분개를 다시 찾을 수 없습니다."));
        return ChargeReversalResult.of(detail(existing), false);
    }

    /**
     * 원거래가 충전 구조인지 확인하고 지갑 항목을 돌려준다. 아니면 422(LEDGER_ORIGINAL_NOT_REVERSIBLE).
     * 기대 구조: 항목 정확히 2개 = SYSTEM CHARGE_SOURCE DEBIT CHARGE_OUT + USER WALLET CREDIT CHARGE_IN, 금액·통화 같음
     */
    private static LedgerEntryLine chargeWalletLine(List<LedgerEntryLine> lines) {
        if (lines.size() != 2) {
            throw new BusinessException(LedgerErrorCode.LEDGER_ORIGINAL_NOT_REVERSIBLE);
        }
        LedgerEntryLine source = lines.stream().filter(LedgerTransactionRecorder::isChargeSourceLine).findFirst()
                .orElseThrow(() -> new BusinessException(LedgerErrorCode.LEDGER_ORIGINAL_NOT_REVERSIBLE));
        LedgerEntryLine wallet = lines.stream().filter(LedgerTransactionRecorder::isChargeWalletLine).findFirst()
                .orElseThrow(() -> new BusinessException(LedgerErrorCode.LEDGER_ORIGINAL_NOT_REVERSIBLE));
        if (source.amount() != wallet.amount() || source.currency() != wallet.currency()) {
            throw new BusinessException(LedgerErrorCode.LEDGER_ORIGINAL_NOT_REVERSIBLE);
        }
        return wallet;
    }

    private static boolean isChargeSourceLine(LedgerEntryLine line) {
        return line.ownerType() == OwnerType.SYSTEM && line.accountType() == AccountType.CHARGE_SOURCE
                && line.direction() == Direction.DEBIT && EntryType.CHARGE_OUT.name().equals(line.entryType());
    }

    private static boolean isChargeWalletLine(LedgerEntryLine line) {
        return line.ownerType() == OwnerType.USER && line.accountType() == AccountType.WALLET
                && line.walletId() != null
                && line.direction() == Direction.CREDIT && EntryType.CHARGE_IN.name().equals(line.entryType());
    }

    /**
     * 잠근 계정에 항목을 반영 순서대로 적용하고 POSTED 거래·항목을 저장한다 (record·reverseCharge 공통).
     * 거래 행을 먼저 저장(flush)하므로 같은 reference면 여기서 DuplicateReferenceException으로 롤백된다
     */
    private LedgerTransaction post(RegisterTransactionCommand command, String requestHash,
                                   Map<UUID, LedgerAccount> accounts, List<ResolvedEntry> resolvedEntries,
                                   Map<UUID, Long> netChanges) {
        Map<UUID, Long> lockedBalances = accounts.values().stream()
                .collect(Collectors.toMap(LedgerAccount::getId, LedgerAccount::getBalance));
        LedgerTransaction transaction = transactionRepository.save(LedgerTransaction.posted(
                command.referenceType(), command.referenceId(), requestHash,
                command.userId(), command.originalTransactionId(), command.description()));
        List<LedgerEntry> entries = new ArrayList<>(resolvedEntries.size());
        for (ResolvedEntry resolved : resolvedEntries) {
            RegisterTransactionCommand.Entry entry = resolved.entry();
            LedgerAccount account = accounts.get(resolved.accountId());
            long balanceAfter = account.apply(entry.direction(), entry.amount());
            entries.add(LedgerEntry.create(transaction.getId(), account.getId(), entry.currency(),
                    entry.entryType(), entry.direction(), entry.amount(), balanceAfter));
        }
        verifyFinalBalances(accounts, lockedBalances, netChanges);
        transactionRepository.saveEntries(entries);
        return transaction;
    }

    private TransactionDetailResult detail(LedgerTransaction transaction) {
        List<LedgerEntryResult> entries = transactionRepository.findEntryLines(transaction.getId()).stream()
                .map(LedgerEntryResult::from)
                .toList();
        return TransactionDetailResult.of(transaction, entries);
    }

    private Optional<UUID> findAccountId(RegisterTransactionCommand.Entry entry) {
        return entry.ownerType() == OwnerType.USER
                ? accountRepository.findUserWalletAccountId(entry.walletId(), entry.currency())
                : accountRepository.findSystemAccountId(entry.accountType(), entry.currency());
    }

    /**
     * 항목을 반영 순서로 정렬한다. 요청 해시가 항목 순서를 무시하므로 반영도 요청 순서와 무관해야 한다.
     * 반영 순서는 결정적이기만 하면 된다(같은 거래는 항상 같은 balance_after). 잠금 순서(DB의 ORDER BY id)와는
     * 별개이며 UUID 비교 방식이 달라 두 순서가 같지 않을 수 있다.
     * 계정 ID 순, 같은 계정 안에서는 CREDIT 먼저: 순변동이 0 이상이면
     * 중간 잔액이 음수가 되지 않아 USER 계정 apply가 실패하지 않는다.
     * 같은 계정·방향 항목이 여럿이면 금액, entryType 순으로 정한다.
     */
    private static List<ResolvedEntry> inApplyOrder(RegisterTransactionCommand command, List<UUID> accountIds) {
        List<ResolvedEntry> resolved = new ArrayList<>(command.entries().size());
        for (int i = 0; i < command.entries().size(); i++) {
            resolved.add(new ResolvedEntry(accountIds.get(i), command.entries().get(i)));
        }
        resolved.sort(Comparator.comparing(ResolvedEntry::accountId)
                .thenComparing(resolvedEntry -> resolvedEntry.entry().direction() == Direction.CREDIT ? 0 : 1)
                .thenComparingLong(resolvedEntry -> resolvedEntry.entry().amount())
                .thenComparing(resolvedEntry -> resolvedEntry.entry().entryType()));
        return resolved;
    }

    /** 계정별 순변동(CREDIT 합 - DEBIT 합) */
    private static Map<UUID, Long> netChanges(List<ResolvedEntry> resolvedEntries) {
        Map<UUID, Long> netChanges = new HashMap<>();
        for (ResolvedEntry resolved : resolvedEntries) {
            RegisterTransactionCommand.Entry entry = resolved.entry();
            long signed = entry.direction() == Direction.CREDIT ? entry.amount() : Math.negateExact(entry.amount());
            netChanges.merge(resolved.accountId(), signed, Math::addExact);
        }
        return netChanges;
    }

    /**
     * 잠금 후의 값으로 상태·잔액을 판단한다. 엔티티는 바꾸지 않는다(거절 시 변경이 커밋되지 않도록).
     * 항목 순서와 무관하게 계정별 순변동으로 판단한다: USER 계정은 현재 잔액 + 순변동이 음수면 거절.
     * SYSTEM 계정은 음수 허용.
     */
    private static Optional<RejectReason> judge(Map<UUID, LedgerAccount> accounts, Map<UUID, Long> netChanges) {
        if (accounts.values().stream().anyMatch(account -> !account.isActive())) {
            return Optional.of(RejectReason.WALLET_SUSPENDED);
        }
        for (Map.Entry<UUID, Long> netChange : netChanges.entrySet()) {
            LedgerAccount account = accounts.get(netChange.getKey());
            if (account.isUserAccount() && Math.addExact(account.getBalance(), netChange.getValue()) < 0) {
                return Optional.of(RejectReason.INSUFFICIENT_BALANCE);
            }
        }
        return Optional.empty();
    }

    /** 반영 후 잔액이 잠금 시점 잔액 + 순변동과 다르면 예외로 트랜잭션 전체를 롤백한다 */
    private static void verifyFinalBalances(Map<UUID, LedgerAccount> accounts, Map<UUID, Long> lockedBalances,
                                            Map<UUID, Long> netChanges) {
        for (Map.Entry<UUID, Long> netChange : netChanges.entrySet()) {
            UUID accountId = netChange.getKey();
            long expected = Math.addExact(lockedBalances.get(accountId), netChange.getValue());
            long actual = accounts.get(accountId).getBalance();
            if (actual != expected) {
                throw new IllegalStateException(
                        "반영 후 잔액이 예상과 다릅니다: " + accountId + " expected=" + expected + " actual=" + actual);
            }
        }
    }

    /** 확정 거절은 REJECTED 거래만 남기고 항목은 없다 */
    private TransactionResult reject(RegisterTransactionCommand command, String requestHash, RejectReason reason) {
        LedgerTransaction transaction = transactionRepository.save(LedgerTransaction.rejected(
                command.referenceType(), command.referenceId(), requestHash,
                command.userId(), reason, command.originalTransactionId(), command.description()));
        return TransactionResult.of(transaction, true);
    }

    private static TransactionResult toExistingResult(LedgerTransaction existing, String requestHash) {
        if (!existing.hasSameRequest(requestHash)) {
            throw new BusinessException(LedgerErrorCode.LEDGER_REFERENCE_CONFLICT);
        }
        return TransactionResult.of(existing, false);
    }

    /** 요청 항목과 그 항목이 가리키는 계정 ID */
    private record ResolvedEntry(UUID accountId, RegisterTransactionCommand.Entry entry) {
    }
}
