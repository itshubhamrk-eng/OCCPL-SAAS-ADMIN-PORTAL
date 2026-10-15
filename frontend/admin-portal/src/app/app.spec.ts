import { provideHttpClient } from '@angular/common/http';
import { provideHttpClientTesting } from '@angular/common/http/testing';
import { TestBed } from '@angular/core/testing';
import { Router, provideRouter, withComponentInputBinding } from '@angular/router';
import { of } from 'rxjs';
import { App } from './app';
import { routes } from './app.routes';
import { MeApi } from './core/api/me.api';
import { AuthService } from './core/auth/auth.service';
import { BROWSER } from './core/auth/browser';
import { APP_CONFIG } from './core/config/app-config';
import { ACME_ADMIN, FakeBrowser, TEST_CONFIG, me } from './testing/fixtures';

describe('App routing', () => {
  function setup(signedIn: boolean, user = me({ memberships: [ACME_ADMIN] })) {
    TestBed.configureTestingModule({
      imports: [App],
      providers: [
        provideRouter(routes, withComponentInputBinding()),
        provideHttpClient(),
        provideHttpClientTesting(),
        { provide: APP_CONFIG, useValue: TEST_CONFIG },
        { provide: BROWSER, useValue: new FakeBrowser() },
        { provide: MeApi, useValue: { me: () => of(user) } },
      ],
    });
    const auth = TestBed.inject(AuthService);
    vi.spyOn(auth, 'isAuthenticated').mockReturnValue(signedIn);
    return vi.spyOn(auth, 'login').mockResolvedValue();
  }

  it('starts sign-in for protected pages when signed out', async () => {
    const login = setup(false);
    TestBed.createComponent(App);
    await TestBed.inject(Router).navigateByUrl('/platform/companies');
    expect(login).toHaveBeenCalledWith('/platform/companies');
  });

  it('sends a company user from / to their company', async () => {
    setup(true);
    const fixture = TestBed.createComponent(App);
    const router = TestBed.inject(Router);
    await router.navigateByUrl('/');
    expect(router.url).toBe('/companies/c-acme');
    await fixture.whenStable();
    expect((fixture.nativeElement as HTMLElement).querySelector('occpl-shell')).not.toBeNull();
  });

  it('refuses the platform area to company users', async () => {
    setup(true);
    TestBed.createComponent(App);
    const router = TestBed.inject(Router);
    await router.navigateByUrl('/platform/companies');
    expect(router.url).toBe('/forbidden');
  });

  it('renders the not-found page for unknown URLs', async () => {
    setup(true);
    const fixture = TestBed.createComponent(App);
    await TestBed.inject(Router).navigateByUrl('/does/not/exist');
    await fixture.whenStable();
    expect((fixture.nativeElement as HTMLElement).querySelector('h1')?.textContent).toContain(
      'Page not found',
    );
  });
});
