package com.payments.settlement;

import java.math.BigDecimal;
import java.util.UUID;

public record NettingResult(UUID partyA, UUID partyB, BigDecimal netAmount) {
}
