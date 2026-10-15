import { HttpClient, HttpHeaders } from '@angular/common/http';
import { Injectable, computed, inject, signal } from '@angular/core';
import { firstValueFrom } from 'rxjs';
import { APP_CONFIG } from '../config/app-config';
import { BROWSER } from './browser';
import { randomUrlSafe, s256Challenge } from './pkce';

interface OidcEndpoints {
  readonly authorization_endpoint: string;
  readonly token_endpoint: string;
  readonly end_session_endpoint?: string;
}

interface TokenResponse {
  readonly access_token: string;
  readonly expires_in: number;
  readonly refresh_token?: string;
  readonly id_token?: string;
}

interface Tokens {
  readonly accessToken: string;
  readonly expiresAt: number;
  readonly refreshToken: string | null;
  readonly idToken: string | null;
}

/** Saved only for the duration of the redirect to the identity provider. */
interface PendingLogin {
  readonly state: string;
  readonly verifier: string;
  readonly returnUrl: string;
}

const PENDING_KEY = 'occpl.auth.pending';
export const CALLBACK_PATH = '/auth/callback';
export const SIGNED_OUT_PATH = '/signed-out';
const REFRESH_MARGIN_MS = 60_000;

/**
 * OpenID Connect sign-in with Authorization Code + PKCE against Keycloak (SECURITY.md §1).
 * Tokens live in memory only: a page reload signs in again through the IdP's single sign-on
 * session (usually without a prompt). Access tokens are refreshed shortly before they expire.
 */
@Injectable({ providedIn: 'root' })
export class AuthService {
  private readonly config = inject(APP_CONFIG);
  private readonly http = inject(HttpClient);
  private readonly browser = inject(BROWSER);

  private readonly tokens = signal<Tokens | null>(null);
  private endpoints?: Promise<OidcEndpoints>;
  private refreshTimer?: ReturnType<typeof setTimeout>;

  readonly isAuthenticated = computed(() => this.tokens() !== null);

  accessToken(): string | null {
    return this.tokens()?.accessToken ?? null;
  }

  /** Redirects to the IdP. {@code forceLogin} asks for credentials again (e.g. to add MFA). */
  async login(returnUrl = '/', options: { forceLogin?: boolean } = {}): Promise<void> {
    const endpoints = await this.discover();
    const verifier = randomUrlSafe(48);
    const pending: PendingLogin = {
      state: randomUrlSafe(16),
      verifier,
      returnUrl: safeReturnUrl(returnUrl),
    };
    this.browser.sessionStorage.setItem(PENDING_KEY, JSON.stringify(pending));
    const params = new URLSearchParams({
      client_id: this.config.oidc.clientId,
      response_type: 'code',
      scope: 'openid',
      redirect_uri: this.redirectUri(),
      state: pending.state,
      code_challenge: await s256Challenge(verifier),
      code_challenge_method: 'S256',
    });
    if (options.forceLogin) {
      params.set('prompt', 'login');
    }
    this.browser.redirect(`${endpoints.authorization_endpoint}?${params}`);
  }

  /** Completes the redirect back from the IdP; returns the in-app URL to continue to. */
  async handleCallback(query: URLSearchParams): Promise<string> {
    const raw = this.browser.sessionStorage.getItem(PENDING_KEY);
    this.browser.sessionStorage.removeItem(PENDING_KEY);
    const error = query.get('error');
    if (error) {
      throw new Error(`Sign-in failed: ${query.get('error_description') ?? error}`);
    }
    const pending = raw ? (JSON.parse(raw) as PendingLogin) : null;
    const code = query.get('code');
    if (!pending || !code || query.get('state') !== pending.state) {
      throw new Error('The sign-in response is invalid or expired. Please sign in again.');
    }
    const endpoints = await this.discover();
    const body = new URLSearchParams({
      grant_type: 'authorization_code',
      client_id: this.config.oidc.clientId,
      code,
      redirect_uri: this.redirectUri(),
      code_verifier: pending.verifier,
    });
    this.store(await this.requestToken(endpoints.token_endpoint, body));
    return pending.returnUrl;
  }

  /** Ends the IdP session too, so the next visit asks for credentials. */
  async logout(): Promise<void> {
    const idToken = this.tokens()?.idToken;
    this.clear();
    const endpoints = await this.discover();
    if (!endpoints.end_session_endpoint) {
      this.browser.redirect(this.browser.origin + SIGNED_OUT_PATH);
      return;
    }
    const params = new URLSearchParams({
      client_id: this.config.oidc.clientId,
      post_logout_redirect_uri: this.browser.origin + SIGNED_OUT_PATH,
    });
    if (idToken) {
      params.set('id_token_hint', idToken);
    }
    this.browser.redirect(`${endpoints.end_session_endpoint}?${params}`);
  }

  /** Uses the refresh token; on failure the user signs in again. Returns whether a token is available. */
  async refresh(): Promise<boolean> {
    const refreshToken = this.tokens()?.refreshToken;
    if (!refreshToken) {
      return false;
    }
    try {
      const endpoints = await this.discover();
      const body = new URLSearchParams({
        grant_type: 'refresh_token',
        client_id: this.config.oidc.clientId,
        refresh_token: refreshToken,
      });
      this.store(await this.requestToken(endpoints.token_endpoint, body));
      return true;
    } catch {
      this.clear();
      return false;
    }
  }

  private requestToken(url: string, body: URLSearchParams): Promise<TokenResponse> {
    return firstValueFrom(
      this.http.post<TokenResponse>(url, body.toString(), {
        headers: new HttpHeaders({ 'Content-Type': 'application/x-www-form-urlencoded' }),
      }),
    );
  }

  private store(response: TokenResponse): void {
    const previous = this.tokens();
    const tokens: Tokens = {
      accessToken: response.access_token,
      expiresAt: Date.now() + response.expires_in * 1000,
      refreshToken: response.refresh_token ?? previous?.refreshToken ?? null,
      idToken: response.id_token ?? previous?.idToken ?? null,
    };
    this.tokens.set(tokens);
    clearTimeout(this.refreshTimer);
    const delay = Math.max(tokens.expiresAt - Date.now() - REFRESH_MARGIN_MS, 5_000);
    this.refreshTimer = setTimeout(() => void this.refresh(), delay);
  }

  private clear(): void {
    clearTimeout(this.refreshTimer);
    this.tokens.set(null);
  }

  private discover(): Promise<OidcEndpoints> {
    this.endpoints ??= firstValueFrom(
      this.http.get<OidcEndpoints>(
        `${this.config.oidc.issuer.replace(/\/+$/, '')}/.well-known/openid-configuration`,
      ),
    ).catch((e: unknown) => {
      this.endpoints = undefined;
      throw e;
    });
    return this.endpoints;
  }

  private redirectUri(): string {
    return this.browser.origin + CALLBACK_PATH;
  }
}

/** Only in-app paths are accepted as return targets (no open redirects). */
export function safeReturnUrl(url: string): string {
  return url.startsWith('/') && !url.startsWith('//') && !url.startsWith(CALLBACK_PATH) ? url : '/';
}
