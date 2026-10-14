package com.aquatrace.api.security;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.aquatrace.api.common.ApiErrorWriter;
import com.google.firebase.auth.FirebaseAuth;
import com.google.firebase.auth.FirebaseAuthException;
import com.google.firebase.auth.FirebaseToken;
import jakarta.servlet.FilterChain;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.core.context.SecurityContextHolder;

class FirebaseTokenAuthenticationFilterTest {
    @AfterEach
    void clearContext() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void verifiesBearerTokenAndSetsFirebasePrincipal() throws Exception {
        FirebaseAuth auth = mock(FirebaseAuth.class);
        FirebaseToken token = mock(FirebaseToken.class);
        ApiErrorWriter errorWriter = mock(ApiErrorWriter.class);
        when(token.getUid()).thenReturn("user-1");
        when(token.getEmail()).thenReturn("user@example.com");
        when(token.getClaims()).thenReturn(java.util.Map.of());
        when(auth.verifyIdToken("valid-token")).thenReturn(token);
        FirebaseTokenAuthenticationFilter filter = new FirebaseTokenAuthenticationFilter(auth, errorWriter);
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/v1/me");
        request.addHeader("Authorization", "Bearer valid-token");
        MockHttpServletResponse response = new MockHttpServletResponse();
        FilterChain chain = mock(FilterChain.class);

        filter.doFilter(request, response, chain);

        FirebasePrincipal principal = (FirebasePrincipal) SecurityContextHolder.getContext().getAuthentication().getPrincipal();
        assertEquals("user-1", principal.uid());
        assertEquals("user@example.com", principal.email());
        verify(chain).doFilter(request, response);
    }

    @Test
    void missingBearerTokenContinuesUnauthenticatedForSecurityEntryPoint() throws Exception {
        FirebaseAuth auth = mock(FirebaseAuth.class);
        ApiErrorWriter errorWriter = mock(ApiErrorWriter.class);
        FirebaseTokenAuthenticationFilter filter = new FirebaseTokenAuthenticationFilter(auth, errorWriter);
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/v1/me");
        MockHttpServletResponse response = new MockHttpServletResponse();
        FilterChain chain = mock(FilterChain.class);

        filter.doFilter(request, response, chain);

        assertNull(SecurityContextHolder.getContext().getAuthentication());
        verify(auth, never()).verifyIdToken("unused");
        verify(chain).doFilter(request, response);
    }
}
