package com.kuropay.wallet.infrastructure.web;

import com.kuropay.wallet.application.port.in.CreateWalletUseCase;
import com.kuropay.wallet.application.port.in.CreateWalletUseCase.CreateWalletCommand;
import com.kuropay.wallet.application.port.in.CreateWalletUseCase.CreateWalletResult;
import com.kuropay.wallet.application.port.in.GetWalletQuery;
import com.kuropay.wallet.domain.model.Currency;
import com.kuropay.wallet.domain.model.Wallet;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import java.math.BigDecimal;
import java.net.URI;
import java.time.Instant;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.support.ServletUriComponentsBuilder;

@RestController
@RequestMapping("/api/wallets")
public class WalletController {

    private final CreateWalletUseCase createWalletUseCase;
    private final GetWalletQuery getWalletQuery;

    public WalletController(CreateWalletUseCase createWalletUseCase, GetWalletQuery getWalletQuery) {
        this.createWalletUseCase = createWalletUseCase;
        this.getWalletQuery = getWalletQuery;
    }

    /**
     * The owner is whoever the access token was issued to, never something the client chooses:
     * otherwise any authenticated user could open wallets in someone else's name.
     *
     * <p>{@code sub} and not {@code preferred_username} or {@code email}: the subject is unique
     * within the issuer and never reassigned, while usernames and emails can change hands.
     */
    @PostMapping(version = "1.0")
    public ResponseEntity<?> createWallet(
            @AuthenticationPrincipal Jwt token,
            @Valid @RequestBody CreateWalletRequest request
    ) {
        CreateWalletResult result = createWalletUseCase.createWallet(
                new CreateWalletCommand(token.getSubject(), request.currency())
        );

        return switch (result) {
            case CreateWalletResult.Created created -> ResponseEntity
                    .created(locationOf(created.wallet().id()))
                    .body(WalletResponse.from(created.wallet()));
            case CreateWalletResult.AlreadyExists alreadyExists -> walletAlreadyExists(alreadyExists.existing());
        };
    }

    @GetMapping(path = "/{walletId}", version = "1.0")
    public WalletResponse getWallet(@PathVariable UUID walletId) {
        return WalletResponse.from(getWalletQuery.getWallet(walletId));
    }

    /** 409 that points at the existing resource, so a retrying client can simply follow it. */
    private static ResponseEntity<ProblemDetail> walletAlreadyExists(Wallet existing) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(
                HttpStatus.CONFLICT,
                "Owner '%s' already has a %s wallet".formatted(existing.ownerId(), existing.currency())
        );
        problem.setTitle("Wallet Already Exists");
        problem.setType(URI.create("https://api.kuropay.com/errors/wallet-already-exists"));
        problem.setProperty("existingWalletId", existing.id());
        return ResponseEntity.status(HttpStatus.CONFLICT)
                .location(locationOf(existing.id()))
                .body(problem);
    }

    private static URI locationOf(UUID walletId) {
        return ServletUriComponentsBuilder.fromCurrentContextPath()
                .path("/api/wallets/{id}")
                .buildAndExpand(walletId)
                .toUri();
    }

    /**
     * Deliberately has no ownerId: the request DTO is an allowlist, so an ownerId sent in the
     * body has nowhere to bind and is dropped (no mass assignment).
     */
    public record CreateWalletRequest(@NotNull Currency currency) {
    }

    public record WalletResponse(
            UUID id,
            String ownerId,
            BigDecimal balance,
            Currency currency,
            Instant createdAt,
            Instant updatedAt
    ) {
        static WalletResponse from(Wallet wallet) {
            return new WalletResponse(
                    wallet.id(),
                    wallet.ownerId(),
                    wallet.balance().amount(),
                    wallet.currency(),
                    wallet.createdAt(),
                    wallet.updatedAt()
            );
        }
    }
}
