import { HttpClient, provideHttpClient, withInterceptors } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { TestBed } from '@angular/core/testing';
import {
  ActivatedRouteSnapshot,
  Router,
  RouterStateSnapshot,
  UrlTree,
  provideRouter,
} from '@angular/router';
import { ACME_ADMIN, FakeBrowser, TEST_CONFIG, me } from '../../testing/fixtures';
import { authInterceptor } from '../auth/auth.interceptor';
import { AuthService } from '../auth/auth.service';
import { BROWSER } from '../auth/browser';
import { authGuard, companyGuard, companyPermissionGuard, platformGuard } from '../auth/guards';
import { APP_CONFIG } from '../config/app-config';
import { SessionService } from './session.service';

describe('SessionService, guards and auth interceptor', () => {
  let http: HttpTestingController;
  let session: SessionService;

  beforeEach(() => {
    localStorage.clear();
    TestBed.configureTestingModule({
      providers: [
        { provide: APP_CONFIG, useValue: TEST_CONFIG },
        { provide: BROWSER, useValue: new FakeBrowser() },
        provideRouter([]),
        provideHttpClient(withInterceptors([authInterceptor])),
        provideHttpClientTesting(),
      ],
    });
    http = TestBed.inject(HttpTestingController);
    session = TestBed.inject(SessionService);
  });

  async function loadMe(value = me()): Promise<void> {
    const loading = session.load();
    http.expectOne(`${TEST_CONFIG.apiBaseUrl}/me`).flush(value);
    await loading;
  }

  function run<T>(fn: () => T): T {
    return TestBed.runInInjectionContext(fn);
  }

  const companyRoute = (companyId: string) =>
    ({
      paramMap: new Map([['companyId', companyId]]),
      pathFromRoot: [],
    }) as unknown as ActivatedRouteSnapshot;

  it('sends platform staff to the platform area and members to their company', async () => {
    await loadMe(
      me({
        platform: {
          roles: ['SUPER_ADMIN'],
          permissions: ['platform.companies.read'],
          mfaRequired: false,
        },
      }),
    );
    expect(session.homeUrl()).toBe('/platform');

    const reload = session.load(true);
    http.expectOne(`${TEST_CONFIG.apiBaseUrl}/me`).flush(me({ memberships: [ACME_ADMIN] }));
    await reload;
    expect(session.homeUrl()).toBe('/companies/c-acme');
  });

  it('sends users without memberships to the no-access page', async () => {
    await loadMe();
    expect(session.homeUrl()).toBe('/no-access');
  });

  it('combines membership and platform permissions', async () => {
    await loadMe(
      me({
        memberships: [ACME_ADMIN],
        platform: {
          roles: ['PLATFORM_SUPPORT'],
          permissions: ['platform.subscriptions.read'],
          mfaRequired: false,
        },
      }),
    );
    expect(session.can('c-acme', 'company.users.manage')).toBe(true);
    expect(session.can('c-acme', 'company.audit.read')).toBe(false);
    expect(session.can('c-other', 'company.subscription.read', 'platform.subscriptions.read')).toBe(
      true,
    );
    expect(session.can('c-other', 'company.users.read', 'platform.companies.read')).toBe(false);
  });

  it('platform guard requires the permission and explains missing MFA', async () => {
    await loadMe(me({ platform: { roles: ['SUPER_ADMIN'], permissions: [], mfaRequired: true } }));
    const result = run(() =>
      platformGuard('platform.companies.read')(
        {} as ActivatedRouteSnapshot,
        {} as RouterStateSnapshot,
      ),
    );
    expect((result as UrlTree).toString()).toBe('/mfa-required');
  });

  it('company guard admits members and platform staff with company read access only', async () => {
    await loadMe(me({ memberships: [ACME_ADMIN] }));
    expect(run(() => companyGuard(companyRoute('c-acme'), {} as RouterStateSnapshot))).toBe(true);
    expect(
      (
        run(() => companyGuard(companyRoute('c-other'), {} as RouterStateSnapshot)) as UrlTree
      ).toString(),
    ).toBe('/forbidden');
    expect(localStorage.getItem('occpl.lastCompanyId')).toBe('c-acme');

    const child = {
      pathFromRoot: [companyRoute('c-acme')],
      paramMap: new Map(),
    } as unknown as ActivatedRouteSnapshot;
    expect(
      run(() => companyPermissionGuard('company.users.read')(child, {} as RouterStateSnapshot)),
    ).toBe(true);
    expect(
      (
        run(() =>
          companyPermissionGuard('company.audit.read')(child, {} as RouterStateSnapshot),
        ) as UrlTree
      ).toString(),
    ).toBe('/forbidden');
  });

  it('auth guard starts sign-in when there is no token', async () => {
    const auth = TestBed.inject(AuthService);
    const login = vi.spyOn(auth, 'login').mockResolvedValue();
    const allowed = await run(() =>
      authGuard({} as ActivatedRouteSnapshot, { url: '/platform/plans' } as RouterStateSnapshot),
    );
    expect(allowed).toBe(false);
    expect(login).toHaveBeenCalledWith('/platform/plans');
  });

  it('adds the bearer token to API calls only and signs in again after a 401', async () => {
    const auth = TestBed.inject(AuthService);
    vi.spyOn(auth, 'accessToken').mockReturnValue('token-123');
    const login = vi.spyOn(auth, 'login').mockResolvedValue();
    const client = TestBed.inject(HttpClient);
    TestBed.inject(Router);

    client.get(`${TEST_CONFIG.apiBaseUrl}/companies`).subscribe({ error: () => undefined });
    client.get('https://cdn.occpl.test/logo.svg').subscribe();
    const api = http.expectOne(`${TEST_CONFIG.apiBaseUrl}/companies`);
    expect(api.request.headers.get('Authorization')).toBe('Bearer token-123');
    expect(
      http.expectOne('https://cdn.occpl.test/logo.svg').request.headers.has('Authorization'),
    ).toBe(false);

    api.flush({}, { status: 401, statusText: 'Unauthorized' });
    expect(login).toHaveBeenCalledTimes(1);
  });
});
