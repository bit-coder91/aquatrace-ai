package com.aquatrace.api.security;

import com.aquatrace.api.common.ApiErrorWriter;
import com.google.firebase.auth.FirebaseAuth;
import com.google.firebase.auth.FirebaseAuthException;
import com.google.firebase.auth.FirebaseToken;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.Locale;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.authentication.WebAuthenticationDetailsSource;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

@Component
public class FirebaseTokenAuthenticationFilter extends OncePerRequestFilter {
    private final FirebaseAuth firebaseAuth;
    private final ApiErrorWriter errorWriter;

    public FirebaseTokenAuthenticationFilter(FirebaseAuth firebaseAuth, ApiErrorWriter errorWriter) {
        this.firebaseAuth = firebaseAuth;
        this.errorWriter = errorWriter;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        return request.getRequestURI().startsWith("/actuator/");
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                    FilterChain filterChain) throws ServletException, IOException {
        String authorization = request.getHeader("Authorization");
        if (authorization == null || !authorization.regionMatches(true, 0, "Bearer ", 0, 7)) {
            filterChain.doFilter(request, response);
            return;
        }

        String idToken = authorization.substring(7).trim();
        if (idToken.isEmpty()) {
            errorWriter.write(request, response, 401, "INVALID_TOKEN", "A valid Firebase ID token is required.");
            return;
        }

        try {
            FirebaseToken token = firebaseAuth.verifyIdToken(idToken);
            FirebasePrincipal principal = new FirebasePrincipal(
                    token.getUid(), token.getEmail(), token.getClaims());
            UsernamePasswordAuthenticationToken authentication =
                    UsernamePasswordAuthenticationToken.authenticated(principal, null, java.util.List.of());
            authentication.setDetails(new WebAuthenticationDetailsSource().buildDetails(request));
            SecurityContext context = SecurityContextHolder.createEmptyContext();
            context.setAuthentication(authentication);
            SecurityContextHolder.setContext(context);
            filterChain.doFilter(request, response);
        } catch (FirebaseAuthException exception) {
            SecurityContextHolder.clearContext();
            errorWriter.write(request, response, 401, "INVALID_TOKEN", "A valid Firebase ID token is required.");
        }
    }
}

