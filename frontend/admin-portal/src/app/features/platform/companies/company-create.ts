import { ChangeDetectionStrategy, Component, inject, signal } from '@angular/core';
import { NonNullableFormBuilder, ReactiveFormsModule, Validators } from '@angular/forms';
import { Router } from '@angular/router';
import { firstValueFrom } from 'rxjs';
import { CompaniesApi } from '../../../core/api/companies.api';
import { FieldError } from '../../../shared/forms/field-error';
import { applyServerErrors } from '../../../shared/forms/server-errors';
import { PageHeader } from '../../../shared/ui/page-header';

/** Creates a company (PENDING) and invites its first Company Admin in one step. */
@Component({
  selector: 'occpl-company-create',
  imports: [ReactiveFormsModule, PageHeader, FieldError],
  template: `
    <occpl-page-header
      heading="New company"
      backLink="/platform/companies"
      backLabel="Companies"
      subtitle="The company is active right away; its first administrator receives an invitation email."
    />
    @for (message of formErrors(); track message) {
      <p class="field-error" role="alert">{{ message }}</p>
    }
    <form [formGroup]="form" (ngSubmit)="submit()" class="stack">
      <section class="card">
        <h2>Company</h2>
        <div class="form">
          <div class="field">
            <label for="displayName">Display name</label>
            <input
              id="displayName"
              formControlName="displayName"
              aria-describedby="displayName-error"
            />
            <occpl-field-error [control]="form.controls.displayName" errorId="displayName-error" />
          </div>
          <div class="field">
            <label for="legalName">Legal name</label>
            <input id="legalName" formControlName="legalName" aria-describedby="legalName-error" />
            <occpl-field-error [control]="form.controls.legalName" errorId="legalName-error" />
          </div>
          <div class="field">
            <label for="code">Code</label>
            <input id="code" formControlName="code" aria-describedby="code-hint code-error" />
            <span class="hint" id="code-hint"
              >Lower-case letters, digits and hyphens; cannot be changed later</span
            >
            <occpl-field-error [control]="form.controls.code" errorId="code-error" />
          </div>
          <div class="field">
            <label for="country">Country</label>
            <input
              id="country"
              formControlName="country"
              maxlength="2"
              aria-describedby="country-hint country-error"
            />
            <span class="hint" id="country-hint">Two-letter code, e.g. IN</span>
            <occpl-field-error [control]="form.controls.country" errorId="country-error" />
          </div>
          <div class="field">
            <label for="taxId">Tax ID (GSTIN)</label>
            <input id="taxId" formControlName="taxId" aria-describedby="taxId-error" />
            <occpl-field-error [control]="form.controls.taxId" errorId="taxId-error" />
          </div>
          <div class="field">
            <label for="timeZone">Time zone</label>
            <input id="timeZone" formControlName="timeZone" aria-describedby="timeZone-error" />
            <occpl-field-error [control]="form.controls.timeZone" errorId="timeZone-error" />
          </div>
        </div>
      </section>
      <section class="card" formGroupName="initialAdmin">
        <h2>First company administrator</h2>
        <div class="form">
          <div class="field">
            <label for="adminEmail">Email</label>
            <input
              id="adminEmail"
              type="email"
              formControlName="email"
              aria-describedby="adminEmail-error"
            />
            <occpl-field-error
              [control]="form.controls.initialAdmin.controls.email"
              errorId="adminEmail-error"
            />
          </div>
          <div class="field">
            <label for="adminFirst">First name</label>
            <input id="adminFirst" formControlName="firstName" />
          </div>
          <div class="field">
            <label for="adminLast">Last name</label>
            <input id="adminLast" formControlName="lastName" />
          </div>
        </div>
      </section>
      <div class="form-actions">
        <button type="submit" class="btn primary" [disabled]="saving()">Create company</button>
      </div>
    </form>
  `,
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class CompanyCreate {
  private readonly api = inject(CompaniesApi);
  private readonly router = inject(Router);
  private readonly fb = inject(NonNullableFormBuilder);

  protected readonly saving = signal(false);
  protected readonly formErrors = signal<string[]>([]);
  protected readonly form = this.fb.group({
    displayName: ['', [Validators.required, Validators.maxLength(200)]],
    legalName: ['', [Validators.required, Validators.maxLength(200)]],
    code: ['', [Validators.required, Validators.pattern(/^[a-z0-9](?:[a-z0-9-]{0,61}[a-z0-9])?$/)]],
    country: ['IN', [Validators.required, Validators.pattern(/^[A-Z]{2}$/)]],
    taxId: ['', Validators.maxLength(64)],
    timeZone: ['Asia/Kolkata', Validators.maxLength(64)],
    initialAdmin: this.fb.group({
      email: ['', [Validators.required, Validators.email, Validators.maxLength(320)]],
      firstName: ['', Validators.maxLength(100)],
      lastName: ['', Validators.maxLength(100)],
    }),
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
      const company = await firstValueFrom(
        this.api.create({
          code: v.code,
          displayName: v.displayName.trim(),
          legalName: v.legalName.trim(),
          country: v.country,
          taxId: v.taxId.trim() || null,
          timeZone: v.timeZone.trim() || null,
          initialAdmin: {
            email: v.initialAdmin.email.trim(),
            firstName: v.initialAdmin.firstName.trim() || null,
            lastName: v.initialAdmin.lastName.trim() || null,
          },
        }),
      );
      await this.router.navigate(['/companies', company.id]);
    } catch (e) {
      this.formErrors.set(applyServerErrors(this.form, e));
    } finally {
      this.saving.set(false);
    }
  }
}
