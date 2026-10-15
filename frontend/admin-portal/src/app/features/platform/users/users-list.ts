import { DatePipe } from '@angular/common';
import { ChangeDetectionStrategy, Component, OnInit, inject, signal } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { RouterLink } from '@angular/router';
import { PlatformUser, UserStatus } from '../../../core/api/models';
import { PlatformApi } from '../../../core/api/platform.api';
import { PageHeader } from '../../../shared/ui/page-header';
import { StatusBadge } from '../../../shared/ui/status-badge';

@Component({
  selector: 'occpl-users-list',
  imports: [FormsModule, RouterLink, PageHeader, StatusBadge, DatePipe],
  template: `
    <occpl-page-header heading="Users" subtitle="Everyone with an account on the platform" />
    <form class="filters" (ngSubmit)="search()" role="search">
      <div class="field">
        <label for="q">Email starts with</label>
        <input id="q" name="q" [(ngModel)]="q" />
      </div>
      <div class="field">
        <label for="status">Status</label>
        <select id="status" name="status" [(ngModel)]="status" (ngModelChange)="search()">
          <option value="">All</option>
          <option value="ACTIVE">active</option>
          <option value="INVITED">invited</option>
          <option value="DISABLED">disabled</option>
        </select>
      </div>
      <button type="submit" class="btn">Search</button>
    </form>
    <div class="table-wrap">
      <table class="data">
        <thead>
          <tr>
            <th>User</th>
            <th>Status</th>
            <th>Last sign-in</th>
          </tr>
        </thead>
        <tbody>
          @for (u of users(); track u.id) {
            <tr>
              <td data-label="User">
                <a [routerLink]="[u.id]">{{ u.displayName || u.email }}</a>
                <div class="muted small">{{ u.email }}</div>
              </td>
              <td data-label="Status"><occpl-status-badge [status]="u.status" /></td>
              <td data-label="Last sign-in">{{ (u.lastLoginAt | date: 'medium') ?? 'Never' }}</td>
            </tr>
          } @empty {
            <tr>
              <td colspan="3" class="muted">{{ loading() ? 'Loading…' : 'No users match.' }}</td>
            </tr>
          }
        </tbody>
      </table>
    </div>
    @if (next()) {
      <p><button type="button" class="btn" (click)="more()">Load more</button></p>
    }
  `,
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class UsersList implements OnInit {
  private readonly api = inject(PlatformApi);
  protected readonly users = signal<PlatformUser[]>([]);
  protected readonly next = signal<string | null>(null);
  protected readonly loading = signal(true);
  protected q = '';
  protected status = '';

  ngOnInit(): void {
    this.search();
  }

  protected search(): void {
    this.users.set([]);
    this.fetch(null);
  }

  protected more(): void {
    this.fetch(this.next());
  }

  private fetch(after: string | null): void {
    this.loading.set(true);
    this.api
      .users({
        q: this.q.trim() || null,
        status: (this.status || null) as UserStatus | null,
        after,
        size: 50,
      })
      .subscribe({
        next: (page) => {
          this.users.update((list) => [...list, ...page.items]);
          this.next.set(page.nextCursor);
          this.loading.set(false);
        },
        error: () => this.loading.set(false),
      });
  }
}
