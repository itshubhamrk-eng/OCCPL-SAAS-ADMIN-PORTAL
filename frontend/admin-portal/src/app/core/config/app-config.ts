import { InjectionToken } from '@angular/core';

/** Runtime configuration loaded from /config.json so one build can be deployed to every environment. */
export interface AppConfig {
  readonly apiBaseUrl: string;
  readonly oidc: {
    readonly issuer: string;
    readonly clientId: string;
  };
}

export const APP_CONFIG = new InjectionToken<AppConfig>('APP_CONFIG');

export function parseAppConfig(raw: unknown): AppConfig {
  if (!isRecord(raw) || !isRecord(raw['oidc'])) {
    throw new Error('Invalid runtime configuration: expected an object with "oidc".');
  }
  const apiBaseUrl = requireUrl(raw['apiBaseUrl'], 'apiBaseUrl');
  const issuer = requireUrl(raw['oidc']['issuer'], 'oidc.issuer');
  const clientId = raw['oidc']['clientId'];
  if (typeof clientId !== 'string' || clientId.trim() === '') {
    throw new Error('Invalid runtime configuration: "oidc.clientId" is required.');
  }
  return { apiBaseUrl: apiBaseUrl.replace(/\/+$/, ''), oidc: { issuer, clientId } };
}

/** Fetches and validates the runtime configuration before the application bootstraps. */
export async function loadAppConfig(fetchFn: typeof fetch = fetch): Promise<AppConfig> {
  const response = await fetchFn('/config.json', { cache: 'no-store' });
  if (!response.ok) {
    throw new Error(`Unable to load runtime configuration (HTTP ${response.status}).`);
  }
  return parseAppConfig(await response.json());
}

function isRecord(value: unknown): value is Record<string, unknown> {
  return typeof value === 'object' && value !== null && !Array.isArray(value);
}

function requireUrl(value: unknown, name: string): string {
  if (typeof value !== 'string') {
    throw new Error(`Invalid runtime configuration: "${name}" is required.`);
  }
  try {
    const url = new URL(value);
    if (url.protocol !== 'https:' && url.protocol !== 'http:') {
      throw new Error();
    }
  } catch {
    throw new Error(`Invalid runtime configuration: "${name}" must be an absolute http(s) URL.`);
  }
  return value;
}
