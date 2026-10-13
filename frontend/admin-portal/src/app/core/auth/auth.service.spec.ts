import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { TestBed } from '@angular/core/testing';
import { APP_CONFIG } from '../config/app-config';
import { DISCOVERY, DISCOVERY_URL, FakeBrowser, TEST_CONFIG } from '../../testing/fixtures';
import { AuthService, safeReturnUrl } from './auth.service';
import { BROWSER } from './browser';

describe('AuthService', () => {
  let auth: AuthService;
  let http: HttpTestingController;
  let browser: FakeBrowser;

  beforeEach(() => {
    browser = new FakeBrowser();
    TestBed.configureTestingModule({
      providers: [
        { provide: APP_CONFIG, useValue: TEST_CONFIG },
        { provide: BROWSER, useValue: browser },
        provideHttpClient(),
        provideHttpClientTesting(),
      ],
    });
    auth = TestBed.inject(AuthService);
    http = TestBed.inject(HttpTestingController);
  });

  afterEach(() => http.verify());

  async function flushDiscovery(): Promise<void> {
    await Promise.resolve();
    http.expectOne(DISCOVERY_URL).flush(DISCOVERY);
    await new Promise((r) => setTimeout(r));
  }

  async function login(returnUrl = '/platform/companies'): Promise<URL> {
    const done = auth.login(returnUrl);
    await flushDiscovery();
    await done;
    return new URL(browser.redirects.at(-1) as string);
  }

  it('redirects to the IdP with PKCE (S256), state and the callback URL', async () => {
    const url = await login();
    expect(url.origin + url.pathname).toBe(DISCOVERY.authorization_endpoint);
    expect(url.searchParams.get('client_id')).toBe('occpl-admin-portal');
    expect(url.searchParams.get('response_type')).toBe('code');
    expect(url.searchParams.get('redirect_uri')).toBe('https://portal.occpl.test/auth/callback');
    expect(url.searchParams.get('code_challenge_method')).toBe('S256');
    expect(url.searchParams.get('code_challenge')).toMatch(/^[A-Za-z0-9_-]{43}$/);
    expect(url.searchParams.get('state')).toBeTruthy();
    expect(url.searchParams.has('prompt')).toBe(false);
  });

  it('exchanges the code with the stored verifier and returns to the original page', async () => {
    const url = await login('/platform/companies');
    const state = url.searchParams.get('state') as string;
    const pending = JSON.parse(browser.sessionStorage.getItem('occpl.auth.pending') as string);

    const result = auth.handleCallback(new URLSearchParams({ code: 'abc', state }));
    await new Promise((r) => setTimeout(r));
    const tokenRequest = http.expectOne(DISCOVERY.token_endpoint);
    const body = new URLSearchParams(tokenRequest.request.body as string);
    expect(body.get('grant_type')).toBe('authorization_code');
    expect(body.get('code')).toBe('abc');
    expect(body.get('code_verifier')).toBe(pending.verifier);
    tokenRequest.flush({
      access_token: 'at-1',
      expires_in: 300,
      refresh_token: 'rt-1',
      id_token: 'id-1',
    });

    expect(await result).toBe('/platform/companies');
    expect(auth.isAuthenticated()).toBe(true);
    expect(auth.accessToken()).toBe('at-1');
    expect(browser.sessionStorage.getItem('occpl.auth.pending')).toBeNull();
  });

  it('rejects a callback whose state does not match', async () => {
    await login();
    await expect(
      auth.handleCallback(new URLSearchParams({ code: 'abc', state: 'forged' })),
    ).rejects.toThrow(/invalid or expired/);
    expect(auth.isAuthenticated()).toBe(false);
  });

  it('reports errors returned by the IdP', async () => {
    await expect(
      auth.handleCallback(
        new URLSearchParams({ error: 'access_denied', error_description: 'User cancelled' }),
      ),
    ).rejects.toThrow(/User cancelled/);
  });

  it('refreshes with the refresh token and signs out locally when refresh fails', async () => {
    const url = await login();
    const result = auth.handleCallback(
      new URLSearchParams({ code: 'c', state: url.searchParams.get('state') as string }),
    );
    await new Promise((r) => setTimeout(r));
    http
      .expectOne(DISCOVERY.token_endpoint)
      .flush({ access_token: 'at-1', expires_in: 300, refresh_token: 'rt-1' });
    await result;

    const ok = auth.refresh();
    await new Promise((r) => setTimeout(r));
    const refresh = http.expectOne(DISCOVERY.token_endpoint);
    expect(new URLSearchParams(refresh.request.body as string).get('refresh_token')).toBe('rt-1');
    refresh.flush({ access_token: 'at-2', expires_in: 300, refresh_token: 'rt-2' });
    expect(await ok).toBe(true);
    expect(auth.accessToken()).toBe('at-2');

    const failed = auth.refresh();
    await new Promise((r) => setTimeout(r));
    http
      .expectOne(DISCOVERY.token_endpoint)
      .flush({ error: 'invalid_grant' }, { status: 400, statusText: 'Bad Request' });
    expect(await failed).toBe(false);
    expect(auth.isAuthenticated()).toBe(false);
  });

  it('logs out at the IdP and returns to the signed-out page', async () => {
    const done = auth.logout();
    await flushDiscovery();
    await done;
    const url = new URL(browser.redirects.at(-1) as string);
    expect(url.origin + url.pathname).toBe(DISCOVERY.end_session_endpoint);
    expect(url.searchParams.get('post_logout_redirect_uri')).toBe(
      'https://portal.occpl.test/signed-out',
    );
  });

  it('forces a fresh login when asked (to add MFA)', async () => {
    const done = auth.login('/', { forceLogin: true });
    await flushDiscovery();
    await done;
    expect(new URL(browser.redirects.at(-1) as string).searchParams.get('prompt')).toBe('login');
  });

  it('accepts only in-app return URLs', () => {
    expect(safeReturnUrl('/companies/1')).toBe('/companies/1');
    expect(safeReturnUrl('https://evil.example')).toBe('/');
    expect(safeReturnUrl('//evil.example')).toBe('/');
    expect(safeReturnUrl('/auth/callback?code=x')).toBe('/');
  });
});
