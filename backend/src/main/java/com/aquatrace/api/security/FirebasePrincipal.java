package com.aquatrace.api.security;

import java.util.Map;

public record FirebasePrincipal(String uid, String email, Map<String, Object> claims) {
}
