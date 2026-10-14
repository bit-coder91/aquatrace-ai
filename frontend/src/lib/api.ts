import { auth } from "@/lib/firebase";

const baseUrl = process.env.NEXT_PUBLIC_API_BASE_URL ?? "http://localhost:8081";

export async function api<T>(path: string, init: RequestInit = {}): Promise<T> {
  const user = auth.currentUser;
  if (!user) throw new Error("Sign in to continue.");
  const token = await user.getIdToken();
  const response = await fetch(`${baseUrl}${path}`, {
    ...init,
    headers: { Authorization: `Bearer ${token}`, ...(init.body instanceof FormData ? {} : { "Content-Type": "application/json" }), ...init.headers },
  });
  const body = await response.json().catch(() => ({}));
  if (!response.ok) throw new Error(body.message ?? `Request failed (${response.status})`);
  return body as T;
}

export type Site = { siteId: string; name: string; timezone: string; role?: "ADMIN" | "PROPERTY_MANAGER" | "VIEWER" };
export type IngestionJob = { jobId: string; status: string; fileName: string; createdAt?: string; inputRows?: number; acceptedRows?: number; rejectedRows?: number };
export type Meter = { meterId: string; label: string; enabled: boolean; meterType: "MAIN" | "SUBMETER"; lastReadingAt?: string | null; freshness?: "CURRENT" | "STALE" | "NO_DATA" };
export type DailyAggregate = { date: string; totalUsageLitres: number; meterCount: number; gapIntervalCount: number; reconciliationDifferenceLitres: number | null };
export type Alert = { alertId: string; type: string; severity: "LOW" | "MEDIUM" | "HIGH"; meterId: string; detectedAt?: string; explanation: string; recommendedInvestigation: string; status: string };
