import { AppConfig } from '../core/config/app-config';
import { BrowserEnvironment } from '../core/auth/browser';
import { Me } from '../core/api/models';

export const TEST_CONFIG: AppConfig = {
  apiBaseUrl: 'https://api.occpl.test/api/v1',
  oidc: { issuer: 'https://id.occpl.test/realms/occpl', clientId: 'occpl-admin-portal' },
};

export const DISCOVERY_URL = 'https://id.occpl.test/realms/occpl/.well-known/openid-configuration';
export const DISCOVERY = {
  authorization_endpoint: 'https://id.occpl.test/realms/occpl/protocol/openid-connect/auth',
  token_endpoint: 'https://id.occpl.test/realms/occpl/protocol/openid-connect/token',
  end_session_endpoint: 'https://id.occpl.test/realms/occpl/protocol/openid-connect/logout',
};

/** Records redirects and keeps session storage in memory. */
export class FakeBrowser implements BrowserEnvironment {
  readonly origin = 'https://portal.occpl.test';
  readonly redirects: string[] = [];
  private readonly store = new Map<string, string>();
  readonly sessionStorage = {
    getItem: (k: string) => this.store.get(k) ?? null,
    setItem: (k: string, v: string) => void this.store.set(k, v),
    removeItem: (k: string) => void this.store.delete(k),
  };

  redirect(url: string): void {
    this.redirects.push(url);
  }
}

export function me(overrides: Partial<Me> = {}): Me {
  return {
    id: 'u-1',
    email: 'asha@acme.test',
    displayName: 'Asha',
    platform: { roles: [], permissions: [], mfaRequired: false },
    memberships: [],
    ...overrides,
  };
}

export const ACME_ADMIN = {
  membershipId: 'm-1',
  companyId: 'c-acme',
  companyCode: 'acme',
  companyName: 'Acme',
  companyStatus: 'ACTIVE' as const,
  roles: ['COMPANY_ADMIN'],
  permissions: [
    'company.profile.read',
    'company.users.read',
    'company.users.manage',
    'company.products.launch',
  ],
};
