import { DatePipe } from '@angular/common';
import { ChangeDetectionStrategy, Component, OnInit, inject, input, signal } from '@angular/core';
import { NonNullableFormBuilder, ReactiveFormsModule, Validators } from '@angular/forms';
import { firstValueFrom } from 'rxjs';
import { LeadsApi } from '../../../core/api/leads.api';
import { Lead, LeadStatus } from '../../../core/api/models';
import { SessionService } from '../../../core/session/session.service';
import { applyServerErrors } from '../../../shared/forms/server-errors';
import { ActionDialog } from '../../../shared/ui/action-dialog';
import { HumanizePipe } from '../../../shared/ui/format.pipes';
import { PageHeader } from '../../../shared/ui/page-header';
import { StatusBadge } from '../../../shared/ui/status-badge';
import { LeadFields, PHONE_PATTERN } from './lead-fields';

/** Mirrors the transitions the API allows; the server is the authority. */
export const NEXT_STATUSES: Record<LeadStatus, LeadStatus[]> = {
  NEW: ['CONTACTED', 'QUALIFIED', 'LOST'],
  CONTACTED: ['QUALIFIED', 'LOST'],
  QUALIFIED: ['CONVERTED', 'LOST'],
  CONVERTED: [],
  LOST: ['NEW'],
};

@Component({
  selector: 'occpl-lead-detail',
  imports: [
    ReactiveFormsModule,
    DatePipe,
    PageHeader,
    StatusBadge,
    ActionDialog,
    LeadFields,
    HumanizePipe,
  ],
  template: `
    @if (lead(); as l) {
      <occpl-page-header
        [heading]="l.companyName"
        [subtitle]="l.contactName"
        backLink="/platform/leads"
        backLabel="Leads"
      >
        <occpl-status-badge [status]="l.status" />
        @if (canManage()) {
          @for (next of nextStatuses(); track next) {
            <button
              type="button"
              class="btn"
              [class.danger]="next === 'LOST'"
              [class.primary]="next === 'CONVERTED'"
              (click)="pending.set(next); statusDialog.open()"
            >
              {{ next === 'NEW' ? 'Reopen' : 'Mark ' + (next | humanize) }}
            </button>
          }
        }
      </occpl-page-header>
      @for (message of statusErrors(); track message) {
        <p class="field-error" role="alert">{{ message }}</p>
      }
      <occpl-action-dialog
        #statusDialog
        heading="Change lead status"
        confirmLabel="Confirm"
        reason="optional"
        (confirmed)="changeStatus($event)"
      />
      <section class="card">
        <div class="row">
          <h2>Details</h2>
          <span class="spacer"></span>
          @if (canManage() && !editing()) {
            <button type="button" class="btn small" (click)="startEdit()">Edit</button>
          }
        </div>
        @if (editing()) {
          @for (message of editErrors(); track message) {
            <p class="field-error" role="alert">{{ message }}</p>
          }
          <form class="stack" [formGroup]="editForm" (ngSubmit)="saveEdit()">
            <occpl-lead-fields [group]="editForm" />
            <div class="form-actions">
              <button type="submit" class="btn primary">Save</button>
              <button type="button" class="btn" (click)="editing.set(false)">Cancel</button>
            </div>
          </form>
        } @else {
          <dl class="details">
            <dt>Email</dt>
            <dd>{{ l.email }}</dd>
            <dt>Phone</dt>
            <dd>{{ l.phone ?? '—' }}</dd>
            <dt>Product interest</dt>
            <dd>{{ l.productInterest ?? '—' }}</dd>
            <dt>Source</dt>
            <dd>{{ l.source ?? '—' }}</dd>
            <dt>Notes</dt>
            <dd>{{ l.notes ?? '—' }}</dd>
            <dt>Created</dt>
            <dd>{{ l.createdAt | date: 'medium' }}</dd>
            <dt>Updated</dt>
            <dd>{{ l.updatedAt | date: 'medium' }}</dd>
          </dl>
        }
      </section>
    }
  `,
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class LeadDetail implements OnInit {
  private readonly api = inject(LeadsApi);
  private readonly fb = inject(NonNullableFormBuilder);
  protected readonly session = inject(SessionService);

  readonly leadId = input.required<string>();
  protected readonly lead = signal<Lead | null>(null);
  protected readonly editing = signal(false);
  protected readonly editErrors = signal<string[]>([]);
  protected readonly statusErrors = signal<string[]>([]);
  protected readonly pending = signal<LeadStatus | null>(null);

  protected readonly editForm = this.fb.group({
    companyName: ['', [Validators.required, Validators.maxLength(200)]],
    contactName: ['', [Validators.required, Validators.maxLength(200)]],
    email: ['', [Validators.required, Validators.email, Validators.maxLength(320)]],
    phone: ['', Validators.pattern(PHONE_PATTERN)],
    productInterest: ['', Validators.maxLength(200)],
    source: ['', Validators.maxLength(100)],
    notes: ['', Validators.maxLength(2000)],
  });

  protected canManage(): boolean {
    return this.session.hasPlatform('platform.leads.manage');
  }

  protected nextStatuses(): LeadStatus[] {
    const l = this.lead();
    return l ? NEXT_STATUSES[l.status] : [];
  }

  async ngOnInit(): Promise<void> {
    this.lead.set(await firstValueFrom(this.api.get(this.leadId())));
  }

  protected startEdit(): void {
    const l = this.lead();
    if (!l) {
      return;
    }
    this.editForm.setValue({
      companyName: l.companyName,
      contactName: l.contactName,
      email: l.email,
      phone: l.phone ?? '',
      productInterest: l.productInterest ?? '',
      source: l.source ?? '',
      notes: l.notes ?? '',
    });
    this.editErrors.set([]);
    this.editing.set(true);
  }

  protected async saveEdit(): Promise<void> {
    const l = this.lead();
    this.editForm.markAllAsTouched();
    if (!l || this.editForm.invalid) {
      return;
    }
    const v = this.editForm.getRawValue();
    try {
      this.lead.set(
        await firstValueFrom(
          this.api.update(l, {
            companyName: v.companyName.trim(),
            contactName: v.contactName.trim(),
            email: v.email.trim(),
            phone: v.phone.trim(),
            productInterest: v.productInterest.trim(),
            source: v.source.trim(),
            notes: v.notes.trim(),
          }),
        ),
      );
      this.editing.set(false);
    } catch (e) {
      this.editErrors.set(applyServerErrors(this.editForm, e));
    }
  }

  protected async changeStatus(reason: string | null): Promise<void> {
    const target = this.pending();
    if (!target) {
      return;
    }
    this.statusErrors.set([]);
    try {
      this.lead.set(await firstValueFrom(this.api.changeStatus(this.leadId(), target, reason)));
    } catch (e) {
      this.statusErrors.set(applyServerErrors(this.editForm, e));
    }
  }
}
