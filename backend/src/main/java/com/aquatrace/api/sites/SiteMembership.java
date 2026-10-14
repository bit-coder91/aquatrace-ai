package com.aquatrace.api.sites;

public record SiteMembership(String siteId, String uid, SiteRole role, String status) {
}
