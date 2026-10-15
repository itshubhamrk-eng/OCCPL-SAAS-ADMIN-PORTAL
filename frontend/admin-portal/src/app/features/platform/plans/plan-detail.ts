import { DatePipe } from '@angular/common';
import { ChangeDetectionStrategy, Component, OnInit, inject, input, signal } from '@angular/core';
import { NonNullableFormBuilder, ReactiveFormsModule, Validators } from '@angular/forms';
import { Router, RouterLink } from '@angular/router';
import { firstValueFrom } from 'rxjs';
import { Plan } from '../../../core/api/models';
import { SubscriptionsApi } from '../../../core/api/subscriptions.api';
import { SessionService } from '../../../core/session/session.service';
import { FieldError } from '../../../shared/forms/field-error';
import { applyServerErrors } from '../../../shared/forms/server-errors';
import { ActionDialog } from '../../../shared/ui/action-dialog';
import { HumanizePipe, MoneyPipe } from '../../../shared/ui/format.pipes';
import { PageHeader } from '../../../shared/ui/page-header';
import { StatusBadge } from '../../../shared/ui/status-badge';

@Component({
  selector: 'occpl-plan-detail',
  imports: [
    ReactiveFormsModule,
    RouterLink,
    PageHeader,
    StatusBadge,
    ActionDialog,
    FieldError,
    DatePipe,
    HumanizePipe,
    MoneyPipe,
  ],
  template: `
    @if (plan(); as p) {
      <occpl-page-header
        [heading]="p.name"
        [subtitle]="p.code"
        backLink="/platform/plans"
        backLabel="Plans"
      >
        <occpl-status-badge [status]="p.status" />
        @if (canManage()) {
          @if (p.status === 'DRAFT') {
            <a class="btn" [routerLink]="['edit']">Edit</a>
            <button type="button" class="btn primary" (click)="publishDialog.open()">
              Publish
            </button>
          }
          @if (p.status === 'ACTIVE') {
            <button type="button" class="btn danger" (click)="archiveDialog.open()">Archive</button>
          }
          <button type="button" class="btn" (click)="copying.set(!copying())">Copy</button>
        }
      </occpl-page-header>
      <occpl-action-dialog
        #publishDialog
        heading="Publish plan"
        confirmLabel="Publish"
        message="Published plans can be assigned to companies and can no longer be changed."
        (confirmed)="act('publish')"
      />
      <occpl-action-dialog
        #archiveDialog
        heading="Archive plan"
        confirmLabel="Archive"
        [danger]="true"
        message="Archived plans cannot be assigned any more. Existing subscriptions are not affected."
        (confirmed)="act('archive')"
      />

      <div class="stack">
        @if (copying()) {
          <section class="card">
            <h2>Copy as a new draft</h2>
            @for (message of copyErrors(); track message) {
              <p class="field-error" role="alert">{{ message }}</p>
            }
            <form class="form" [formGroup]="copyForm" (ngSubmit)="copy()">
              <div class="field">
                <label for="copyCode">New code</label>
                <input id="copyCode" formControlName="code" aria-describedby="copyCode-error" />
                <occpl-field-error [control]="copyForm.controls.code" errorId="copyCode-error" />
              </div>
              <div class="field">
                <label for="copyName">New name</label>
                <input id="copyName" formControlName="name" />
              </div>
              <div class="form-actions">
                <button type="submit" class="btn primary">Create copy</button>
              </div>
            </form>
          </section>
        }
        <section class="card">
          <dl class="details">
            <dt>Billing period</dt>
            <dd>{{ p.billingPeriod | humanize }}</dd>
            <dt>Trial</dt>
            <dd>{{ p.trialDays ? p.trialDays + ' days' : 'None' }}</dd>
            <dt>Prices</dt>
            <dd>
              @for (price of p.prices; track price.currency) {
                <div>{{ price.amountMinor | money: price.currency }}</div>
              }
            </dd>
            <dt>Description</dt>
            <dd>{{ p.description ?? '—' }}</dd>
            <dt>Published</dt>
            <dd>{{ (p.publishedAt | date: 'medium') ?? 'Not yet' }}</dd>
          </dl>
        </section>
        @for (product of p.products; track product.productId) {
          <section class="card">
            <h2>{{ product.productName }}</h2>
            <div class="table-wrap">
              <table class="data">
                <thead>
                  <tr>
                    <th>Feature</th>
                    <th>Included</th>
                    <th>Limit</th>
                  </tr>
                </thead>
                <tbody>
                  @for (f of product.features; track f.featureId) {
                    <tr>
                      <td data-label="Feature">
                        {{ f.name }}
                        <div class="mono muted">{{ f.code }}</div>
                      </td>
                      <td data-label="Included">{{ f.enabled ? 'Yes' : 'No' }}</td>
                      <td data-label="Limit">
                        @if (f.type === 'LIMIT' && f.enabled) {
                          {{ f.limit === null ? 'Unlimited' : f.limit }} {{ f.unit | humanize }}
                          @if (f.resetPeriod && f.resetPeriod !== 'NONE') {
                            <span class="muted small">per {{ f.resetPeriod | humanize }}</span>
                          }
                        } @else {
                          —
                        }
                      </td>
                    </tr>
                  }
                </tbody>
              </table>
            </div>
          </section>
        }
      </div>
    } @else {
      <p role="status">Loading…</p>
    }
  `,
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class PlanDetail implements OnInit {
  private readonly api = inject(SubscriptionsApi);
  private readonly router = inject(Router);
  private readonly fb = inject(NonNullableFormBuilder);
  protected readonly session = inject(SessionService);

  readonly planId = input.required<string>();
  protected readonly plan = signal<Plan | null>(null);
  protected readonly copying = signal(false);
  protected readonly copyErrors = signal<string[]>([]);
  protected readonly copyForm = this.fb.group({
    code: ['', [Validators.required, Validators.pattern(/^[a-z][a-z0-9-]{1,63}$/)]],
    name: ['', Validators.maxLength(200)],
  });

  protected canManage(): boolean {
    return this.session.hasPlatform('platform.plans.manage');
  }

  async ngOnInit(): Promise<void> {
    this.plan.set(await firstValueFrom(this.api.plan(this.planId())));
  }

  protected async act(action: 'publish' | 'archive'): Promise<void> {
    this.plan.set(await firstValueFrom(this.api.planAction(this.planId(), action)));
  }

  protected async copy(): Promise<void> {
    this.copyForm.markAllAsTouched();
    if (this.copyForm.invalid) {
      return;
    }
    const v = this.copyForm.getRawValue();
    try {
      const copy = await firstValueFrom(
        this.api.copyPlan(this.planId(), v.code, v.name.trim() || null),
      );
      await this.router.navigate(['/platform/plans', copy.id]);
      this.plan.set(copy);
      this.copying.set(false);
    } catch (e) {
      this.copyErrors.set(applyServerErrors(this.copyForm, e));
    }
  }
}
