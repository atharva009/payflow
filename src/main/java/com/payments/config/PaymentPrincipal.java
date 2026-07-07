package com.payments.config;

import java.util.List;
import java.util.UUID;

public record PaymentPrincipal(UUID sub, List<UUID> accountIds, String role) {
}
