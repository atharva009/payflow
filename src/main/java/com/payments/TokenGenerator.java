package com.payments;

import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.MACSigner;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;

import java.util.Date;
import java.util.List;
import java.util.UUID;

public class TokenGenerator {

    public static void main(String[] args) throws Exception {
        String secret = "test-secret-key-minimum-32-chars-ok";
        UUID accountId = UUID.randomUUID();

        JWTClaimsSet claims = new JWTClaimsSet.Builder()
            .subject(UUID.randomUUID().toString())
            .claim("accountIds", List.of(accountId.toString()))
            .claim("role", "USER")
            .expirationTime(new Date(System.currentTimeMillis() + 3600_000))
            .build();

        SignedJWT jwt = new SignedJWT(
            new JWSHeader(JWSAlgorithm.HS256),
            claims
        );
        jwt.sign(new MACSigner(secret.getBytes()));

        System.out.println("JWT:       " + jwt.serialize());
        System.out.println("accountId: " + accountId);
    }
}