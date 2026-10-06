package com.rohit.nyvra.accounts;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

import com.rohit.nyvra.accounts.dto.AccountResponse;
import com.rohit.nyvra.accounts.dto.CardRequest;
import com.rohit.nyvra.accounts.dto.CreateAccountRequest;
import com.rohit.nyvra.accounts.dto.UpdateAccountRequest;
import com.rohit.nyvra.common.api.PageResponse;
import com.rohit.nyvra.common.exception.BadRequestException;
import com.rohit.nyvra.common.exception.ConflictException;
import com.rohit.nyvra.common.exception.ResourceNotFoundException;
import com.rohit.nyvra.common.exception.UnprocessableEntityException;
import com.rohit.nyvra.common.money.Money;
import com.rohit.nyvra.common.persistence.RecordSource;
import com.rohit.nyvra.user.CurrentUserService;
import jakarta.persistence.criteria.Predicate;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Account use cases for the signed-in user. Every lookup is scoped to the caller, so another user's
 * account is indistinguishable from a missing one (404, never 403).
 */
@Service
public class AccountService {

    /** The only currency supported in v1 (API_DESIGN §3). */
    private static final String SUPPORTED_CURRENCY = Money.INR;

    private static final Set<String> SORTABLE = Set.of("label", "type", "balanceAsOf", "currentBalance");

    private static final String SOURCE_READ_ONLY = "SOURCE_READ_ONLY";
    private static final String ACCOUNT_CLOSED = "ACCOUNT_CLOSED";

    private final FinancialAccountRepository accounts;
    private final CardDetailRepository cards;
    private final CurrentUserService currentUser;

    public AccountService(FinancialAccountRepository accounts, CardDetailRepository cards,
                          CurrentUserService currentUser) {
        this.accounts = accounts;
        this.cards = cards;
        this.currentUser = currentUser;
    }

    @Transactional(readOnly = true)
    public PageResponse<AccountResponse> list(Collection<AccountType> types, Collection<AccountStatus> statuses,
                                              RecordSource source, int page, int size, List<String> sort) {
        UUID userId = currentUser.currentUser().getId();
        Set<AccountStatus> wanted = statuses == null || statuses.isEmpty()
            ? Set.of(AccountStatus.ACTIVE, AccountStatus.STALE)
            : Set.copyOf(statuses);

        Specification<FinancialAccount> spec = (root, query, cb) -> {
            List<Predicate> where = new ArrayList<>();
            where.add(cb.equal(root.get("userId"), userId));
            where.add(cb.isNull(root.get("deletedAt")));
            where.add(root.get("status").in(wanted));
            if (types != null && !types.isEmpty()) {
                where.add(root.get("type").in(types));
            }
            if (source != null) {
                where.add(cb.equal(root.get("source"), source));
            }
            return cb.and(where.toArray(Predicate[]::new));
        };

        Pageable pageable = PageRequest.of(page, size, parseSort(sort));
        Page<FinancialAccount> result = accounts.findAll(spec, pageable);

        Map<UUID, CardDetail> cardsByAccount = cards
            .findByFinancialAccountIdIn(result.stream().map(FinancialAccount::getId).toList()).stream()
            .collect(Collectors.toMap(CardDetail::getFinancialAccountId, Function.identity()));
        return PageResponse.of(result, a -> AccountResponse.from(a, cardsByAccount.get(a.getId())));
    }

    @Transactional(readOnly = true)
    public AccountResponse get(UUID id) {
        FinancialAccount account = owned(id);
        return AccountResponse.from(account, cardOf(account).orElse(null));
    }

    @Transactional
    public AccountResponse create(CreateAccountRequest request) {
        UUID userId = currentUser.currentUser().getId();
        Money balance = request.currentBalance().toMoney();
        requireSupportedCurrency(balance.currency());

        boolean isCard = request.type() == AccountType.CREDIT_CARD;
        if (isCard && request.card() == null) {
            throw new BadRequestException("card is required when type is CREDIT_CARD");
        }
        if (!isCard && request.card() != null) {
            throw new BadRequestException("card is only allowed when type is CREDIT_CARD");
        }

        Instant asOf = request.balanceAsOf() != null ? request.balanceAsOf() : Instant.now();
        FinancialAccount account = accounts.save(new FinancialAccount(
            userId, request.type(), request.institution(), request.maskedNumber(), request.label(),
            balance, asOf, RecordSource.MANUAL));

        CardDetail card = null;
        if (isCard) {
            CardRequest c = request.card();
            Money limit = c.creditLimit() == null ? null : c.creditLimit().toMoney();
            if (limit != null) {
                requireSameCurrency(account, limit);
            }
            card = cards.save(new CardDetail(
                account.getId(), c.last4(), c.network(), c.label(), account.getCurrency(), limit));
        }
        return AccountResponse.from(account, card);
    }

    @Transactional
    public AccountResponse update(UUID id, UpdateAccountRequest request) {
        FinancialAccount account = owned(id);
        if (account.getStatus() == AccountStatus.CLOSED) {
            throw new ConflictException(ACCOUNT_CLOSED, "Closed accounts can't be edited");
        }

        boolean touchesSyncedFields = request.currentBalance() != null || request.balanceAsOf() != null
            || (request.card() != null && request.card().creditLimit() != null);
        if (touchesSyncedFields && account.getSource() != RecordSource.MANUAL) {
            throw new ConflictException(SOURCE_READ_ONLY,
                "Balance and credit limit are kept in sync with the bank and can't be edited");
        }

        if (request.label() != null) {
            account.rename(request.label());
        }
        if (request.institution() != null) {
            account.changeInstitution(request.institution());
        }
        if (request.currentBalance() != null || request.balanceAsOf() != null) {
            Money balance = request.currentBalance() != null
                ? request.currentBalance().toMoney() : account.getCurrentBalance();
            requireSameCurrency(account, balance);
            account.updateBalance(balance, request.balanceAsOf() != null ? request.balanceAsOf() : Instant.now());
        }

        CardDetail card = cardOf(account).orElse(null);
        if (request.card() != null) {
            if (card == null) {
                throw new BadRequestException("This account has no card to update");
            }
            if (request.card().label() != null) {
                card.rename(request.card().label());
            }
            if (request.card().creditLimit() != null) {
                Money limit = request.card().creditLimit().toMoney();
                requireSameCurrency(account, limit);
                card.changeCreditLimit(limit);
            }
        }
        return AccountResponse.from(account, card);
    }

    /** Idempotent: closing a closed account returns it unchanged. */
    @Transactional
    public AccountResponse close(UUID id) {
        FinancialAccount account = owned(id);
        account.changeStatus(AccountStatus.CLOSED);
        return AccountResponse.from(account, cardOf(account).orElse(null));
    }

    @Transactional
    public void delete(UUID id) {
        FinancialAccount account = owned(id);
        if (account.getSource() != RecordSource.MANUAL) {
            throw new ConflictException(SOURCE_READ_ONLY, "Linked accounts can't be deleted — close them instead");
        }
        account.softDelete(Instant.now());
    }

    private FinancialAccount owned(UUID id) {
        UUID userId = currentUser.currentUser().getId();
        return accounts.findByIdAndUserIdAndDeletedAtIsNull(id, userId)
            .orElseThrow(() -> ResourceNotFoundException.of("Account", id));
    }

    private Optional<CardDetail> cardOf(FinancialAccount account) {
        return account.getType() == AccountType.CREDIT_CARD
            ? cards.findByFinancialAccountId(account.getId()).stream().findFirst()
            : Optional.empty();
    }

    private static void requireSupportedCurrency(String currency) {
        if (!SUPPORTED_CURRENCY.equals(currency)) {
            throw new UnprocessableEntityException("UNSUPPORTED_CURRENCY", "Only INR is supported");
        }
    }

    private static void requireSameCurrency(FinancialAccount account, Money money) {
        if (!account.getCurrency().equals(money.currency())) {
            throw new UnprocessableEntityException(
                "UNSUPPORTED_CURRENCY", "Amounts must be in the account's currency (" + account.getCurrency() + ")");
        }
    }

    /** {@code field,asc|desc} entries; unknown fields are a 400, default is {@code type,asc}. */
    private static Sort parseSort(List<String> sort) {
        if (sort == null || sort.isEmpty()) {
            return Sort.by(Sort.Order.asc("type"), Sort.Order.asc("id"));
        }
        List<Sort.Order> orders = new ArrayList<>();
        for (String entry : sort) {
            String[] parts = entry.split(",");
            String field = parts[0].trim();
            if (!SORTABLE.contains(field) || parts.length > 2) {
                throw new BadRequestException("Can't sort accounts by '" + entry + "'");
            }
            boolean desc = parts.length == 2 && parts[1].trim().equalsIgnoreCase("desc");
            if (parts.length == 2 && !desc && !parts[1].trim().equalsIgnoreCase("asc")) {
                throw new BadRequestException("Sort direction must be asc or desc: '" + entry + "'");
            }
            orders.add(desc ? Sort.Order.desc(field) : Sort.Order.asc(field));
        }
        orders.add(Sort.Order.asc("id"));
        return Sort.by(orders);
    }
}
