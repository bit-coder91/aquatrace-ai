package com.aquatrace.api.sites;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.aquatrace.api.common.ApiException;
import com.google.api.core.ApiFutures;
import com.google.cloud.firestore.CollectionReference;
import com.google.cloud.firestore.DocumentReference;
import com.google.cloud.firestore.DocumentSnapshot;
import com.google.cloud.firestore.Firestore;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;

class SiteAuthorizationServiceTest {
    private Firestore firestore;
    private DocumentSnapshot member;
    private SiteAuthorizationService service;

    @BeforeEach
    void setUp() {
        firestore = mock(Firestore.class);
        member = mock(DocumentSnapshot.class);
        CollectionReference sites = mock(CollectionReference.class);
        DocumentReference site = mock(DocumentReference.class);
        CollectionReference members = mock(CollectionReference.class);
        DocumentReference user = mock(DocumentReference.class);
        when(firestore.collection("sites")).thenReturn(sites);
        when(sites.document("site-1")).thenReturn(site);
        when(site.collection("members")).thenReturn(members);
        when(members.document("user-1")).thenReturn(user);
        when(user.get()).thenReturn(ApiFutures.immediateFuture(member));
        service = new SiteAuthorizationService(firestore, 1);
    }

    @Test
    void returnsActiveMembershipForAuthorizedUser() {
        when(member.exists()).thenReturn(true);
        when(member.getString("status")).thenReturn("ACTIVE");
        when(member.getString("role")).thenReturn("PROPERTY_MANAGER");

        SiteMembership result = service.requireAnyRole("site-1", "user-1", SiteRole.ADMIN, SiteRole.PROPERTY_MANAGER);

        assertEquals(SiteRole.PROPERTY_MANAGER, result.role());
        assertEquals("user-1", result.uid());
    }

    @Test
    void hidesSiteWhenMembershipIsMissing() {
        when(member.exists()).thenReturn(false);

        ApiException exception = assertThrows(ApiException.class,
                () -> service.requireMembership("site-1", "user-1"));

        assertEquals(HttpStatus.NOT_FOUND, exception.status());
        assertEquals("SITE_NOT_FOUND", exception.code());
    }

    @Test
    void rejectsViewerForManagerAction() {
        when(member.exists()).thenReturn(true);
        when(member.getString("status")).thenReturn("ACTIVE");
        when(member.getString("role")).thenReturn("VIEWER");

        ApiException exception = assertThrows(ApiException.class,
                () -> service.requireAnyRole("site-1", "user-1", SiteRole.ADMIN, SiteRole.PROPERTY_MANAGER));

        assertEquals(HttpStatus.FORBIDDEN, exception.status());
        assertEquals("INSUFFICIENT_SITE_ROLE", exception.code());
    }
}
