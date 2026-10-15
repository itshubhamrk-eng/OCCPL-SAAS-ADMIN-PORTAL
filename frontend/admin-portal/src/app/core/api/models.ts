// Types of the Core Platform API (see API_SPEC.md and /v3/api-docs). Instants are ISO-8601 strings.

export interface Page<T> {
  readonly items: T[];
  readonly page: number;
  readonly size: number;
  readonly totalItems: number;
  readonly totalPages: number;
}

export interface CursorPage<T, C = string> {
  readonly items: T[];
  readonly nextCursor: C | null;
}

// ---------------------------------------------------------------- me / identity

export interface Me {
  readonly id: string;
  readonly email: string;
  readonly displayName: string | null;
  readonly platform: {
    readonly roles: string[];
    readonly permissions: string[];
    readonly mfaRequired: boolean;
  };
  readonly memberships: MyMembership[];
}

export type CompanyStatus = 'PENDING' | 'ACTIVE' | 'SUSPENDED' | 'DEACTIVATED';

export interface MyMembership {
  readonly membershipId: string;
  readonly companyId: string;
  readonly companyCode: string;
  readonly companyName: string;
  readonly companyStatus: CompanyStatus;
  readonly roles: string[];
  readonly permissions: string[];
}

export interface Profile {
  readonly userId: string;
  readonly email: string;
  readonly displayName: string | null;
  readonly phone: string | null;
  readonly locale: string | null;
  readonly timeZone: string | null;
}

export type UserStatus = 'INVITED' | 'ACTIVE' | 'DISABLED';

export interface PlatformUser {
  readonly id: string;
  readonly email: string;
  readonly displayName: string | null;
  readonly status: UserStatus;
  readonly lastLoginAt: string | null;
  readonly createdAt: string;
  /** Loaded on the detail endpoint only; null in lists. */
  readonly platformRoles: string[] | null;
}

export interface Role {
  readonly id: string;
  readonly code: string;
  readonly scope: 'PLATFORM' | 'COMPANY';
  readonly systemRole: boolean;
  readonly name: string;
  readonly description: string | null;
  readonly permissions: string[];
}

export interface UserMembership {
  readonly membershipId: string;
  readonly companyId: string;
  readonly companyCode: string;
  readonly companyName: string;
  readonly companyStatus: CompanyStatus;
  readonly status: MembershipStatus;
  readonly roles: string[];
  readonly joinedAt: string | null;
}

// ---------------------------------------------------------------- companies & members

export interface Company {
  readonly id: string;
  readonly code: string;
  readonly legalName: string;
  readonly displayName: string;
  readonly status: CompanyStatus;
  readonly country: string;
  readonly taxId: string | null;
  readonly createdAt: string;
  readonly updatedAt: string;
  readonly version: number;
}

export interface NewCompany {
  code: string;
  legalName: string;
  displayName: string;
  country: string;
  taxId?: string | null;
  timeZone?: string | null;
  locale?: string | null;
  primaryContact?: Contact | null;
  initialAdmin: { email: string; firstName?: string | null; lastName?: string | null };
}

export interface CompanyChanges {
  legalName?: string;
  displayName?: string;
  country?: string;
  taxId?: string;
}

export interface CompanySettings {
  readonly timeZone: string;
  readonly locale: string;
  readonly allowedEmailDomains: string[];
  readonly version: number;
}

export type ContactType = 'PRIMARY' | 'BILLING' | 'TECHNICAL';

export interface Contact {
  name: string;
  email: string;
  phone?: string | null;
}

export interface CompanyContact extends Contact {
  readonly type: ContactType;
}

export interface StatusChange {
  readonly fromStatus: CompanyStatus | null;
  readonly toStatus: CompanyStatus;
  readonly reason: string | null;
  readonly changedBy: string | null;
  readonly changedAt: string;
}

export type MembershipStatus = 'INVITED' | 'ACTIVE' | 'SUSPENDED' | 'DEACTIVATED';

export interface Member {
  readonly membershipId: string;
  readonly userId: string;
  readonly email: string;
  readonly displayName: string | null;
  readonly status: MembershipStatus;
  readonly roles: string[];
  readonly joinedAt: string | null;
  readonly createdAt: string;
}

export type InvitationStatus = 'PENDING' | 'ACCEPTED' | 'REVOKED' | 'EXPIRED';

export interface Invitation {
  readonly id: string;
  readonly membershipId: string;
  readonly email: string;
  readonly status: InvitationStatus;
  readonly roles: string[];
  readonly expiresAt: string;
  readonly invitedBy: string | null;
  readonly createdAt: string;
  readonly acceptedAt: string | null;
}

export interface AcceptedInvitation {
  readonly membershipId: string;
  readonly companyId: string;
  readonly companyCode: string;
  readonly companyName: string;
  readonly roles: string[];
}

// ---------------------------------------------------------------- catalogue

export type ProductStatus = 'DRAFT' | 'ACTIVE' | 'RETIRED';

export interface Product {
  readonly id: string;
  readonly code: string;
  readonly name: string;
  readonly description: string | null;
  readonly status: ProductStatus;
  readonly appUrl: string;
  readonly apiBaseUrl: string | null;
  readonly apiAudience: string;
  readonly serviceClientId: string | null;
  readonly currentVersionNo: number | null;
  readonly createdAt: string;
  readonly updatedAt: string;
  readonly version: number;
}

export interface ProductData {
  code?: string;
  name?: string;
  description?: string;
  appUrl?: string;
  apiBaseUrl?: string;
  apiAudience?: string;
  serviceClientId?: string;
}

export interface ProductVersion {
  readonly versionNo: number;
  readonly schemaVersion: number;
  readonly status: 'DRAFT' | 'PUBLISHED';
  readonly current: boolean;
  readonly notes: string | null;
  readonly createdAt: string;
  readonly createdBy: string | null;
  readonly publishedAt: string | null;
  readonly config: unknown;
}

export type FeatureType = 'BOOLEAN' | 'LIMIT';
export type FeatureUnit =
  | 'SEATS'
  | 'STORAGE_GB'
  | 'RECORDS'
  | 'API_CALLS'
  | 'TRANSACTIONS'
  | 'DOCUMENTS'
  | 'MESSAGES'
  | 'COUNT';
export type ResetPeriod = 'NONE' | 'DAILY' | 'MONTHLY' | 'BILLING_PERIOD';

export interface ProductFeature {
  readonly id: string;
  readonly code: string;
  readonly name: string;
  readonly description: string | null;
  readonly type: FeatureType;
  readonly unit: FeatureUnit | null;
  readonly resetPeriod: ResetPeriod | null;
  readonly status: 'ACTIVE' | 'DEPRECATED';
  readonly createdAt: string;
  readonly version: number;
}

export interface NewFeature {
  code: string;
  name: string;
  description?: string | null;
  type: FeatureType;
  unit?: FeatureUnit | null;
  resetPeriod?: ResetPeriod | null;
}

// ---------------------------------------------------------------- plans & subscriptions

export type PlanStatus = 'DRAFT' | 'ACTIVE' | 'ARCHIVED';
export type BillingPeriod = 'MONTHLY' | 'QUARTERLY' | 'HALF_YEARLY' | 'ANNUAL';

export interface Price {
  currency: string;
  amountMinor: number;
}

export interface Plan {
  readonly id: string;
  readonly code: string;
  readonly name: string;
  readonly description: string | null;
  readonly status: PlanStatus;
  readonly billingPeriod: BillingPeriod;
  readonly trialDays: number;
  readonly prices: Price[];
  readonly products: PlanProduct[];
  readonly createdAt: string;
  readonly publishedAt: string | null;
  readonly version: number;
}

export interface PlanProduct {
  readonly productId: string;
  readonly productCode: string;
  readonly productName: string;
  readonly features: PlanFeature[];
}

export interface PlanFeature {
  readonly featureId: string;
  readonly code: string;
  readonly name: string;
  readonly type: FeatureType;
  readonly unit: FeatureUnit | null;
  readonly resetPeriod: ResetPeriod | null;
  readonly enabled: boolean;
  readonly limit: number | null;
}

export interface PlanBody {
  name: string;
  description?: string | null;
  billingPeriod: BillingPeriod;
  trialDays: number;
  prices: Price[];
  products: {
    productId: string;
    features: { code: string; enabled?: boolean; limit?: number | null }[];
  }[];
}

export type SubscriptionStatus =
  'TRIAL' | 'ACTIVE' | 'PAST_DUE' | 'SUSPENDED' | 'CANCELLED' | 'EXPIRED';

export interface Subscription {
  readonly id: string;
  readonly companyId: string;
  readonly planId: string;
  readonly planCode: string;
  readonly planName: string;
  readonly status: SubscriptionStatus;
  readonly currency: string;
  readonly priceMinor: number;
  readonly billingPeriod: BillingPeriod;
  readonly currentPeriodStart: string;
  readonly currentPeriodEnd: string;
  readonly trialEnd: string | null;
  readonly graceUntil: string | null;
  readonly cancelAtPeriodEnd: boolean;
  readonly pendingPlanId: string | null;
  readonly cancelledAt: string | null;
  readonly endedAt: string | null;
  readonly dataRetentionUntil: string | null;
  readonly createdAt: string;
  readonly history: SubscriptionEvent[];
}

export interface SubscriptionEvent {
  readonly event: string;
  readonly fromStatus: SubscriptionStatus | null;
  readonly toStatus: SubscriptionStatus | null;
  readonly fromPlanId: string | null;
  readonly toPlanId: string | null;
  readonly actorUserId: string | null;
  readonly reason: string | null;
  readonly occurredAt: string;
}

export type EntitlementStatus = 'ACTIVE' | 'GRACE' | 'SUSPENDED';

export interface ProductEntitlement {
  readonly productId: string;
  readonly productCode: string;
  readonly productName: string;
  readonly appUrl: string;
  readonly status: EntitlementStatus;
  readonly validUntil: string | null;
  readonly features: EntitlementFeature[];
}

export interface EntitlementFeature {
  readonly code: string;
  readonly name: string;
  readonly type: FeatureType;
  readonly unit: FeatureUnit | null;
  readonly resetPeriod: ResetPeriod | null;
  readonly enabled: boolean;
  readonly limit: number | null;
  readonly used: number | null;
  readonly periodStart: string | null;
}

// ---------------------------------------------------------------- provisioning & audit

export type ProvisioningState =
  | 'PROVISIONING'
  | 'PROVISIONED'
  | 'FAILED'
  | 'DEPROVISION_SCHEDULED'
  | 'DEPROVISIONING'
  | 'DEPROVISIONED';

export interface CompanyProduct {
  readonly productId: string;
  readonly productCode: string;
  readonly productName: string;
  readonly entitlementStatus: EntitlementStatus;
  readonly validUntil: string | null;
  readonly provisioningState: ProvisioningState | null;
  readonly launchUrl: string | null;
  readonly openInNewTab: boolean;
}

export type JobStatus =
  'PENDING' | 'RUNNING' | 'AWAITING_CALLBACK' | 'SUCCEEDED' | 'FAILED' | 'CANCELLED';

export interface ProvisioningJob {
  readonly id: string;
  readonly companyId: string;
  readonly companyCode: string | null;
  readonly productId: string;
  readonly productCode: string | null;
  readonly operation: 'PROVISION' | 'DEPROVISION';
  readonly status: JobStatus;
  readonly attempts: number;
  readonly maxAttempts: number;
  readonly nextAttemptAt: string | null;
  readonly callbackDeadline: string | null;
  readonly configVersionNo: number | null;
  readonly lastHttpStatus: number | null;
  readonly lastError: string | null;
  readonly createdAt: string;
  readonly updatedAt: string;
  readonly completedAt: string | null;
}

export interface AuditEntry {
  readonly id: string;
  readonly occurredAt: string;
  readonly actorType: 'USER' | 'SERVICE' | 'SYSTEM';
  readonly actorUserId: string | null;
  readonly actorClientId: string | null;
  readonly companyId: string | null;
  readonly action: string;
  readonly targetType: string;
  readonly targetId: string | null;
  readonly outcome: 'SUCCESS' | 'FAILURE';
  readonly correlationId: string | null;
  readonly details: Record<string, unknown> | null;
}

// ---------------------------------------------------------------- billing

export type InvoiceStatus =
  'CREATING' | 'ISSUE_FAILED' | 'ISSUED' | 'PARTIALLY_PAID' | 'PAID' | 'CANCELLED' | 'EXPIRED';

export interface Invoice {
  readonly id: string;
  readonly companyId: string;
  readonly subscriptionId: string;
  readonly status: InvoiceStatus;
  readonly description: string;
  readonly currency: string;
  readonly amountMinor: number;
  readonly amountPaidMinor: number;
  readonly periodStart: string;
  readonly periodEnd: string;
  readonly gatewayInvoiceId: string | null;
  readonly gatewayNumber: string | null;
  /** Only on open invoices, for viewers allowed to pay. */
  readonly paymentUrl: string | null;
  readonly issuedAt: string | null;
  readonly expiresAt: string | null;
  readonly paidAt: string | null;
  readonly paymentApplied: 'APPLIED' | 'ALREADY_COVERED' | 'NOT_APPLICABLE' | null;
  /** Platform staff only. */
  readonly lastError: string | null;
  readonly createdAt: string;
  readonly payments: Payment[] | null;
}

export interface Payment {
  readonly gatewayPaymentId: string;
  readonly status: 'CAPTURED' | 'FAILED';
  readonly amountMinor: number;
  readonly currency: string;
  readonly method: string | null;
  readonly errorReason: string | null;
  readonly occurredAt: string;
}

export interface BillingStatus {
  readonly enabled: boolean;
  readonly webhooksEnabled: boolean;
  readonly gateway: string;
}
