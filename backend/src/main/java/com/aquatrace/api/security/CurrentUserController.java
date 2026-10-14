package com.aquatrace.api.security;

import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/me")
public class CurrentUserController {
    @GetMapping
    public CurrentUserResponse currentUser(@AuthenticationPrincipal FirebasePrincipal principal) {
        return new CurrentUserResponse(principal.uid(), principal.email());
    }

    public record CurrentUserResponse(String uid, String email) {
    }
}
