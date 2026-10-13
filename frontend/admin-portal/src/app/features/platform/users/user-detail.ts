import { DatePipe } from '@angular/common';
import { ChangeDetectionStrategy, Component, OnInit, inject, input, signal } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { RouterLink } from '@angular/router';
import { firstValueFrom } from 'rxjs';
import { PlatformUser, Role, UserMembership } from '../../../core/api/models';
import { PlatformApi } from '../../../core/api/platform.api';
import { SessionService } from '../../../core/session/session.service';
import { ActionDialog } from '../../../shared/ui/action-dialog';
import { PageHeader } from '../../../shared/ui/page-header';
import { StatusBadge } from '../../../shared/ui/status-badge';

@Component({
  selector: 'occpl-user-detail',
  imports: [FormsModule, RouterLink, PageHeader, StatusBadge, ActionDialog, DatePipe],
  template: `
    @if (user(); as u) {
      <occpl-page-header
        [heading]="u.displayName || u.email"
        [subtitle]="u.email"
        backLink="/platform/users"
        backLabel="Users"
      >
        @if (session.hasPlatform('platform.users.manage') && u.id !== session.me()?.id) {
          @if (u.status === 'DISABLED') {
            <button type="button" class="btn" (click)="setEnabled(true, null)">Enable</button>
          } @else {
            <button type="button" class="btn danger" (click)="disableDialog.open()">Disable</button>
          }
        }
      </occpl-page-header>
      <occpl-action-dialog
        #disableDialog
        heading="Disable user"
        confirmLabel="Disable"
        [danger]="true"
        reason="optional"
        message="The user is signed out everywhere and cannot sign in until enabled again."
        (confirmed)="setEnabled(false, $event)"
      />
      <div class="stack">
        <section class="card">
          <dl class="details">
            <dt>Status</dt>
            <dd><occpl-status-badge [status]="u.status" /></dd>
            <dt>Last sign-in</dt>
            <dd>{{ (u.lastLoginAt | date: 'medium') ?? 'Never' }}</dd>
            <dt>Created</dt>
            <dd>{{ u.createdAt | date: 'medium' }}</dd>
            <dt>User ID</dt>
            <dd class="mono">{{ u.id }}</dd>
          </dl>
        </section>

        @if (session.hasPlatform('platform.roles.manage')) {
          <section class="card">
            <h2>Platform roles</h2>
            <p class="muted small">
              Platform roles require an authenticator app; the user is asked to set one up at the
              next sign-in.
            </p>
            <fieldset class="roles">
              <legend class="sr-only">Platform roles</legend>
              @for (role of roles(); track role.code) {
                <label class="check">
                  <input
                    type="checkbox"
                    [checked]="selected().has(role.code)"
                    (change)="toggle(role.code)"
                  />
                  <span
                    ><strong>{{ role.name }}</strong>
                    <span class="muted small">{{ role.description }}</span></span
                  >
                </label>
              }
            </fieldset>
            <button
              type="button"
              class="btn primary"
              [disabled]="!rolesChanged()"
              (click)="saveRoles()"
            >
              Save roles
            </button>
            @if (rolesSaved()) {
              <span class="muted small" role="status">Saved.</span>
            }
          </section>
        }

        <section class="card">
          <h2>Company memberships</h2>
          <div class="table-wrap">
            <table class="data">
              <thead>
                <tr>
                  <th>Company</th>
                  <th>Roles</th>
                  <th>Status</th>
                  <th>Joined</th>
                </tr>
              </thead>
              <tbody>
                @for (m of memberships(); track m.membershipId) {
                  <tr>
                    <td data-label="Company">
                      <a [routerLink]="['/companies', m.companyId]">{{ m.companyName }}</a>
                    </td>
                    <td data-label="Roles">{{ m.roles.join(', ') }}</td>
                    <td data-label="Status"><occpl-status-badge [status]="m.status" /></td>
                    <td data-label="Joined">{{ (m.joinedAt | date: 'mediumDate') ?? '—' }}</td>
                  </tr>
                } @empty {
                  <tr>
                    <td colspan="4" class="muted">No company memberships.</td>
                  </tr>
                }
              </tbody>
            </table>
          </div>
        </section>
      </div>
    } @else {
      <p role="status">Loading…</p>
    }
  `,
  styles: `
    .roles {
      border: 0;
      padding: 0;
      margin: 0 0 var(--occpl-space-3);
    }
  `,
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class UserDetail implements OnInit {
  private readonly api = inject(PlatformApi);
  protected readonly session = inject(SessionService);

  readonly userId = input.required<string>();
  protected readonly user = signal<PlatformUser | null>(null);
  protected readonly memberships = signal<UserMembership[]>([]);
  protected readonly roles = signal<Role[]>([]);
  protected readonly selected = signal<Set<string>>(new Set());
  protected readonly rolesSaved = signal(false);

  async ngOnInit(): Promise<void> {
    const user = await firstValueFrom(this.api.user(this.userId()));
    this.user.set(user);
    this.selected.set(new Set(user.platformRoles ?? []));
    this.api.userMemberships(user.id).subscribe((m) => this.memberships.set(m));
    if (this.session.hasPlatform('platform.roles.manage')) {
      this.api.platformRoles().subscribe((r) => this.roles.set(r));
    }
  }

  protected toggle(code: string): void {
    this.rolesSaved.set(false);
    this.selected.update((s) => {
      const next = new Set(s);
      if (next.has(code)) {
        next.delete(code);
      } else {
        next.add(code);
      }
      return next;
    });
  }

  protected rolesChanged(): boolean {
    const current = new Set(this.user()?.platformRoles ?? []);
    const selected = this.selected();
    return current.size !== selected.size || [...selected].some((r) => !current.has(r));
  }

  protected async saveRoles(): Promise<void> {
    const result = await firstValueFrom(
      this.api.replaceUserRoles(this.userId(), [...this.selected()]),
    );
    this.user.update((u) => (u ? { ...u, platformRoles: result.roles } : u));
    this.selected.set(new Set(result.roles));
    this.rolesSaved.set(true);
  }

  protected async setEnabled(enabled: boolean, reason: string | null): Promise<void> {
    this.user.set(await firstValueFrom(this.api.setUserEnabled(this.userId(), enabled, reason)));
  }
}
