import { ChangeDetectionStrategy, Component, inject, signal } from '@angular/core';
import { NonNullableFormBuilder, ReactiveFormsModule, Validators } from '@angular/forms';
import { Router } from '@angular/router';
import { firstValueFrom } from 'rxjs';
import { LeadsApi } from '../../../core/api/leads.api';
import { applyServerErrors } from '../../../shared/forms/server-errors';
import { PageHeader } from '../../../shared/ui/page-header';
import { LeadFields, PHONE_PATTERN } from './lead-fields';

@Component({
  selector: 'occpl-lead-register',
  imports: [ReactiveFormsModule, PageHeader, LeadFields],
  template: `
    <occpl-page-header
      heading="Register lead"
      backLink="/platform/leads"
      backLabel="Leads"
      subtitle="Leads start as new; move them forward as you follow up."
    />
    @for (message of formErrors(); track message) {
      <p class="field-error" role="alert">{{ message }}</p>
    }
    <form class="card stack" [formGroup]="form" (ngSubmit)="submit()">
      <occpl-lead-fields [group]="form" />
      <div class="form-actions">
        <button type="submit" class="btn primary" [disabled]="saving()">Register</button>
      </div>
    </form>
  `,
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class LeadRegister {
  private readonly api = inject(LeadsApi);
  private readonly router = inject(Router);
  private readonly fb = inject(NonNullableFormBuilder);
  protected readonly saving = signal(false);
  protected readonly formErrors = signal<string[]>([]);
  protected readonly form = this.fb.group({
    companyName: ['', [Validators.required, Validators.maxLength(200)]],
    contactName: ['', [Validators.required, Validators.maxLength(200)]],
    email: ['', [Validators.required, Validators.email, Validators.maxLength(320)]],
    phone: ['', Validators.pattern(PHONE_PATTERN)],
    productInterest: ['', Validators.maxLength(200)],
    source: ['', Validators.maxLength(100)],
    notes: ['', Validators.maxLength(2000)],
  });

  protected async submit(): Promise<void> {
    this.form.markAllAsTouched();
    if (this.form.invalid) {
      return;
    }
    this.saving.set(true);
    this.formErrors.set([]);
    const v = this.form.getRawValue();
    try {
      const lead = await firstValueFrom(
        this.api.register({
          companyName: v.companyName.trim(),
          contactName: v.contactName.trim(),
          email: v.email.trim(),
          phone: v.phone.trim() || undefined,
          productInterest: v.productInterest.trim() || undefined,
          source: v.source.trim() || undefined,
          notes: v.notes.trim() || undefined,
        }),
      );
      await this.router.navigate(['/platform/leads', lead.id]);
    } catch (e) {
      this.formErrors.set(applyServerErrors(this.form, e));
    } finally {
      this.saving.set(false);
    }
  }
}
