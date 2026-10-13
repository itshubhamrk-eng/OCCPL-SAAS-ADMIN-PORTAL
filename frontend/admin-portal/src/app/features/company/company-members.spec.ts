import { TestBed } from '@angular/core/testing';
import { of } from 'rxjs';
import { MembersApi } from '../../core/api/members.api';
import { SessionService } from '../../core/session/session.service';
import { CompanyContext } from './company-context';
import { CompanyMembers } from './company-members';

const PAGE = (items: unknown[]) => ({
  items,
  page: 0,
  size: 20,
  totalItems: items.length,
  totalPages: 1,
});

describe('CompanyMembers', () => {
  function setup(canManage: boolean) {
    const api = {
      list: vi.fn().mockReturnValue(
        of(
          PAGE([
            {
              membershipId: 'm-1',
              userId: 'u-me',
              email: 'me@acme.test',
              displayName: 'Me',
              status: 'ACTIVE',
              roles: ['COMPANY_ADMIN'],
              joinedAt: null,
              createdAt: '',
            },
            {
              membershipId: 'm-2',
              userId: 'u-2',
              email: 'ravi@acme.test',
              displayName: 'Ravi',
              status: 'ACTIVE',
              roles: ['COMPANY_USER'],
              joinedAt: null,
              createdAt: '',
            },
          ]),
        ),
      ),
      invitations: vi.fn().mockReturnValue(of(PAGE([]))),
      roles: vi.fn().mockReturnValue(
        of([
          { code: 'COMPANY_ADMIN', name: 'Company admin', description: '' },
          { code: 'COMPANY_USER', name: 'Company user', description: '' },
        ]),
      ),
      invite: vi.fn().mockReturnValue(of({ id: 'i-1', email: 'new@acme.test' })),
    };
    TestBed.configureTestingModule({
      imports: [CompanyMembers],
      providers: [
        { provide: MembersApi, useValue: api },
        {
          provide: CompanyContext,
          useValue: { id: () => 'c-acme', can: () => canManage, isPlatform: () => false },
        },
        { provide: SessionService, useValue: { me: () => ({ id: 'u-me' }) } },
      ],
    });
    const fixture = TestBed.createComponent(CompanyMembers);
    return { fixture, api, el: fixture.nativeElement as HTMLElement };
  }

  it('hides management actions without permission and never offers actions on yourself', async () => {
    const readOnly = setup(false);
    await readOnly.fixture.whenStable();
    expect(readOnly.el.textContent).not.toContain('Invite people');
    expect(readOnly.el.querySelectorAll('td.actions button').length).toBe(0);
    TestBed.resetTestingModule();

    const manager = setup(true);
    await manager.fixture.whenStable();
    const rows = manager.el.querySelectorAll('tbody tr');
    expect(rows[0].querySelectorAll('td.actions button').length).toBe(0); // (you)
    expect(rows[1].textContent).toContain('Suspend');
  });

  it('invites with the chosen roles', async () => {
    const { fixture, el, api } = setup(true);
    await fixture.whenStable();
    (el.querySelector('.card .btn.primary') as HTMLButtonElement).click();
    await fixture.whenStable();
    const email = el.querySelector('#inv-email') as HTMLInputElement;
    email.value = 'new@acme.test';
    email.dispatchEvent(new Event('input'));
    (el.querySelector('form.form button[type="submit"]') as HTMLButtonElement).click();
    await fixture.whenStable();
    expect(api.invite).toHaveBeenCalledWith('c-acme', {
      email: 'new@acme.test',
      roles: ['COMPANY_USER'],
      firstName: null,
      lastName: null,
    });
    expect(el.querySelector('[role="status"]')?.textContent).toContain(
      'Invitation sent to new@acme.test',
    );
  });
});
