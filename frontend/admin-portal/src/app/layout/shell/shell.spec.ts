import { provideHttpClient } from '@angular/common/http';
import { provideHttpClientTesting } from '@angular/common/http/testing';
import { TestBed } from '@angular/core/testing';
import { provideRouter } from '@angular/router';
import { MeApi } from '../../core/api/me.api';
import { BROWSER } from '../../core/auth/browser';
import { APP_CONFIG } from '../../core/config/app-config';
import { ErrorNotifier } from '../../core/errors/error-notifier';
import { SessionService } from '../../core/session/session.service';
import { ACME_ADMIN, FakeBrowser, TEST_CONFIG, me } from '../../testing/fixtures';
import { of } from 'rxjs';
import { Shell } from './shell';

describe('Shell', () => {
  async function render(user = me()) {
    TestBed.configureTestingModule({
      imports: [Shell],
      providers: [
        provideRouter([]),
        provideHttpClient(),
        provideHttpClientTesting(),
        { provide: APP_CONFIG, useValue: TEST_CONFIG },
        { provide: BROWSER, useValue: new FakeBrowser() },
        { provide: MeApi, useValue: { me: () => of(user) } },
      ],
    });
    await TestBed.inject(SessionService).load();
    const fixture = TestBed.createComponent(Shell);
    await fixture.whenStable();
    return fixture;
  }

  it('shows only the platform sections the user may open', async () => {
    const fixture = await render(
      me({
        platform: {
          roles: ['PLATFORM_SUPPORT'],
          permissions: ['platform.companies.read', 'platform.audit.read'],
          mfaRequired: false,
        },
      }),
    );
    const links = [...(fixture.nativeElement as HTMLElement).querySelectorAll('#main-nav a')].map(
      (a) => a.textContent?.trim(),
    );
    expect(links).toEqual(['Dashboard', 'Companies', 'Audit log']);
  });

  it('collapses the menu to icons and remembers it', async () => {
    localStorage.removeItem('occpl.nav-collapsed');
    const fixture = await render(
      me({
        platform: {
          roles: ['SUPER_ADMIN'],
          permissions: ['platform.companies.read'],
          mfaRequired: false,
        },
      }),
    );
    const el = fixture.nativeElement as HTMLElement;
    const side = el.querySelector('.shell-side') as HTMLElement;
    expect(side.classList.contains('collapsed')).toBe(false);
    (el.querySelector('.collapse-toggle') as HTMLButtonElement).click();
    await fixture.whenStable();
    expect(side.classList.contains('collapsed')).toBe(true);
    expect(localStorage.getItem('occpl.nav-collapsed')).toBe('true');
    expect(el.querySelector('#main-nav a')?.getAttribute('title')).toBe('Dashboard');
    (el.querySelector('.collapse-toggle') as HTMLButtonElement).click();
    await fixture.whenStable();
    expect(side.classList.contains('collapsed')).toBe(false);
    expect(localStorage.getItem('occpl.nav-collapsed')).toBe('false');
    localStorage.removeItem('occpl.nav-collapsed');
  });

  it('lists the companies of a company user and no platform section', async () => {
    const fixture = await render(me({ memberships: [ACME_ADMIN] }));
    const el = fixture.nativeElement as HTMLElement;
    expect(el.textContent).not.toContain('OCCPL platform');
    expect(el.querySelector('#main-nav a')?.textContent).toContain('Acme');
    expect(el.querySelector('.user')?.textContent).toContain('Asha');
  });

  it('asks platform staff without MFA to sign in with their authenticator', async () => {
    const fixture = await render(
      me({ platform: { roles: ['SUPER_ADMIN'], permissions: [], mfaRequired: true } }),
    );
    expect(
      (fixture.nativeElement as HTMLElement).querySelector('[role="status"]')?.textContent,
    ).toContain('authenticator');
  });

  it('shows and dismisses the current API error', async () => {
    const fixture = await render();
    TestBed.inject(ErrorNotifier).notify({
      status: 500,
      code: 'INTERNAL_ERROR',
      message: 'Something failed.',
      correlationId: 'ref-123456',
      fieldErrors: [],
    });
    await fixture.whenStable();
    const el = fixture.nativeElement as HTMLElement;
    expect(el.querySelector('[role="alert"]')?.textContent).toContain('Something failed.');
    expect(el.querySelector('.alert-reference')?.textContent).toContain('ref-123456');
    (el.querySelector('.alert-dismiss') as HTMLButtonElement).click();
    await fixture.whenStable();
    expect(el.querySelector('[role="alert"]')).toBeNull();
  });
});
