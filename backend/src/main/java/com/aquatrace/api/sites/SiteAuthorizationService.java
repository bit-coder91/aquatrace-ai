package com.aquatrace.api.sites;

import com.aquatrace.api.common.ApiException;
import com.google.cloud.firestore.DocumentSnapshot;
import com.google.cloud.firestore.Firestore;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

@Service
public class SiteAuthorizationService {
    private final Firestore firestore;
    private final long timeoutSeconds;

    public SiteAuthorizationService(Firestore firestore,
            @Value("${aquatrace.firestore.operation-timeout-seconds:4}") long timeoutSeconds) {
        this.firestore = firestore;
        this.timeoutSeconds = timeoutSeconds;
    }

    public SiteMembership requireMembership(String siteId, String uid) {
        try {
            DocumentSnapshot member = firestore.collection("sites")
                    .document(siteId)
                    .collection("members")
                    .document(uid)
                    .get()
                    .get(timeoutSeconds, TimeUnit.SECONDS);

            String status = member.getString("status");
            String roleValue = member.getString("role");
            if (!member.exists() || !"ACTIVE".equals(status) || roleValue == null) {
                throw notFound();
            }

            SiteRole role;
            try {
                role = SiteRole.valueOf(roleValue);
            } catch (IllegalArgumentException exception) {
                throw new ApiException(HttpStatus.INTERNAL_SERVER_ERROR, "INVALID_MEMBERSHIP", "Site membership is misconfigured.");
            }
            return new SiteMembership(siteId, uid, role, status);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw unavailable();
        } catch (ExecutionException | TimeoutException exception) {
            throw unavailable();
        }
    }

    public SiteMembership requireAnyRole(String siteId, String uid, SiteRole... allowedRoles) {
        SiteMembership membership = requireMembership(siteId, uid);
        for (SiteRole allowedRole : allowedRoles) {
            if (membership.role() == allowedRole) {
                return membership;
            }
        }
        throw new ApiException(HttpStatus.FORBIDDEN, "INSUFFICIENT_SITE_ROLE", "Your site role cannot perform this action.");
    }

    private ApiException notFound() {
        return new ApiException(HttpStatus.NOT_FOUND, "SITE_NOT_FOUND", "The site was not found.");
    }

    private ApiException unavailable() {
        return new ApiException(HttpStatus.SERVICE_UNAVAILABLE, "AUTHORIZATION_STORE_UNAVAILABLE",
                "Site authorization could not be checked. Please retry.");
    }
}
