package com.example.metermonitor.security;

import com.example.metermonitor.config.AppProperties;
import com.example.metermonitor.user.Account;
import io.jsonwebtoken.*;
import io.jsonwebtoken.io.Decoders;
import io.jsonwebtoken.security.Keys;
import java.time.Instant;
import java.util.Date;
import javax.crypto.SecretKey;
import org.springframework.stereotype.Service;

@Service
public class JwtService {
    private final AppProperties properties;
    private final SecretKey key;

    public JwtService(AppProperties properties) {
        this.properties = properties;
        this.key = Keys.hmacShaKeyFor(Decoders.BASE64.decode(properties.jwt().secret()));
    }

    public String issue(Account account) {
        Instant now = Instant.now();
        return Jwts.builder().subject(account.getId().toString())
                .claim("role", account.getRole().name())
                .issuedAt(Date.from(now))
                .expiration(Date.from(now.plus(properties.jwt().expiration())))
                .signWith(key).compact();
    }

    public String subject(String token) {
        return Jwts.parser().verifyWith(key).build().parseSignedClaims(token).getPayload().getSubject();
    }
}
