package com.aquatrace.api.sites;

import com.aquatrace.api.common.ApiException;
import com.google.cloud.firestore.DocumentReference;
import com.google.cloud.firestore.DocumentSnapshot;
import com.google.cloud.firestore.FieldValue;
import com.google.cloud.firestore.Firestore;
import com.google.cloud.firestore.QuerySnapshot;
import com.google.cloud.firestore.WriteBatch;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

@Service
public class SiteService {
    private final Firestore firestore;
    private final SiteAuthorizationService authorizationService;
    private final long timeoutSeconds;

    public SiteService(Firestore firestore, SiteAuthorizationService authorizationService,
                       @Value("${aquatrace.firestore.operation-timeout-seconds:4}") long timeoutSeconds) {
        this.firestore = firestore;
        this.authorizationService = authorizationService;
        this.timeoutSeconds = timeoutSeconds;
    }

    public SiteSummary create(String uid, CreateSiteRequest request) {
        String name = request.name().trim();
        String timezone = request.timezone().trim();
        try {
            ZoneId.of(timezone);
        } catch (RuntimeException exception) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "INVALID_TIMEZONE", "Use a valid IANA timezone such as Asia/Kolkata.");
        }

        DocumentReference site = firestore.collection("sites").document();
        DocumentReference member = site.collection("members").document(uid);
        DocumentReference reverseMembership = firestore.collection("users").document(uid)
                .collection("siteMemberships").document(site.getId());
        Map<String, Object> siteDocument = new HashMap<>();
        siteDocument.put("name", name);
        siteDocument.put("timezone", timezone);
        siteDocument.put("createdBy", uid);
        siteDocument.put("createdAt", FieldValue.serverTimestamp());

        Map<String, Object> membershipDocument = new HashMap<>();
        membershipDocument.put("siteId", site.getId());
        membershipDocument.put("role", SiteRole.ADMIN.name());
        membershipDocument.put("status", "ACTIVE");
        membershipDocument.put("joinedAt", FieldValue.serverTimestamp());
        membershipDocument.put("name", name);
        membershipDocument.put("timezone", timezone);

        try {
            WriteBatch batch = firestore.batch();
            batch.set(site, siteDocument);
            batch.set(member, membershipDocument);
            batch.set(reverseMembership, membershipDocument);
            batch.commit().get(timeoutSeconds, TimeUnit.SECONDS);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw unavailable();
        } catch (ExecutionException | TimeoutException exception) {
            throw unavailable();
        }
        return new SiteSummary(site.getId(), name, timezone, SiteRole.ADMIN);
    }

    public List<SiteSummary> listForUser(String uid) {
        try {
            QuerySnapshot memberships = firestore.collection("users").document(uid)
                    .collection("siteMemberships").get().get(timeoutSeconds, TimeUnit.SECONDS);
            List<SiteSummary> result = new ArrayList<>();
            for (DocumentSnapshot document : memberships.getDocuments()) {
                if (!"ACTIVE".equals(document.getString("status"))) {
                    continue;
                }
                String siteId = document.getString("siteId");
                String name = document.getString("name");
                String timezone = document.getString("timezone");
                String roleValue = document.getString("role");
                if (siteId != null && name != null && timezone != null && roleValue != null) {
                    result.add(new SiteSummary(siteId, name, timezone, SiteRole.valueOf(roleValue)));
                }
            }
            return result;
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw unavailable();
        } catch (ExecutionException | TimeoutException | IllegalArgumentException exception) {
            throw unavailable();
        }
    }

    public SiteSummary get(String uid, String siteId) {
        SiteMembership membership = authorizationService.requireMembership(siteId, uid);
        try {
            DocumentSnapshot site = firestore.collection("sites").document(siteId)
                    .get().get(timeoutSeconds, TimeUnit.SECONDS);
            if (!site.exists()) {
                throw new ApiException(HttpStatus.NOT_FOUND, "SITE_NOT_FOUND", "The site was not found.");
            }
            return new SiteSummary(siteId, site.getString("name"), site.getString("timezone"), membership.role());
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw unavailable();
        } catch (ExecutionException | TimeoutException exception) {
            throw unavailable();
        }
    }

    private ApiException unavailable() {
        return new ApiException(HttpStatus.SERVICE_UNAVAILABLE, "FIRESTORE_UNAVAILABLE",
                "The data store could not complete this request. Please retry.");
    }
}
