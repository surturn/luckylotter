package com.lucklotter.service;

import org.springframework.stereotype.Component;

import java.security.SecureRandom;

/**
 * Generates the short code a customer quotes to claim an offer.
 *
 * <p>Crockford's base-32 alphabet: no {@code I}, {@code L}, {@code O} or
 * {@code U}. The first three are unreadable next to 1 and 0 when a code is
 * printed on a receipt or read down a phone line, and dropping U keeps the
 * alphabet from spelling anything unfortunate.
 *
 * <p>Eight characters over 32 symbols is roughly 10^12 combinations, so
 * collisions inside one business are vanishingly unlikely — and the unique
 * index means a collision fails loudly rather than handing two customers the
 * same code.
 */
@Component
public class RedemptionCodeGenerator {

    private static final char[] ALPHABET = "0123456789ABCDEFGHJKMNPQRSTVWXYZ".toCharArray();
    private static final int LENGTH = 8;
    /** Grouped for legibility when read aloud: XXXX-XXXX. */
    private static final int GROUP = 4;

    private final SecureRandom random = new SecureRandom();

    public String generate() {
        StringBuilder code = new StringBuilder(LENGTH + 1);
        for (int index = 0; index < LENGTH; index++) {
            if (index > 0 && index % GROUP == 0) {
                code.append('-');
            }
            code.append(ALPHABET[random.nextInt(ALPHABET.length)]);
        }
        return code.toString();
    }
}
