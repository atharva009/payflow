package com.payments.account;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

@RestController
@RequestMapping("/api/v1/accounts")
@Tag(name = "Accounts", description = "Account onboarding and lookup")
public class AccountController {

    private final AccountService accountService;

    public AccountController(AccountService accountService) {
        this.accountService = accountService;
    }

    @Operation(summary = "Create account")
    @ApiResponse(responseCode = "201", description = "Account created")
    @PostMapping
    public ResponseEntity<Account> create(@RequestBody CreateAccountRequest request) {
        Account account = accountService.create(request.ownerId(), request.currency());
        return ResponseEntity.status(HttpStatus.CREATED).body(account);
    }

    @Operation(summary = "Get account by ID")
    @ApiResponse(responseCode = "200", description = "Account found")
    @ApiResponse(responseCode = "404", description = "Account not found")
    @GetMapping("/{id}")
    public ResponseEntity<Account> getById(@PathVariable UUID id) {
        return ResponseEntity.ok(accountService.findById(id));
    }

    // Simple request DTO — a record, not @Data. Jackson binds via the canonical constructor.
    public record CreateAccountRequest(UUID ownerId, String currency) {
    }
}
