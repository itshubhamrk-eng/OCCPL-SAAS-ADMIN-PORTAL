import { ChangeDetectionStrategy, Component, inject, signal } from '@angular/core';
import { NonNullableFormBuilder, ReactiveFormsModule, Validators } from '@angular/forms';
import { Router, RouterLink } from '@angular/router';
import { firstValueFrom } from 'rxjs';
import { CompaniesApi } from '../../../core/api/companies.api';
import { Contact } from '../../../core/api/models';
import {
  IMAGE_LIMITS,
  PHONE,
  imageProblem,
  profileControls,
} from '../../../shared/forms/profile-validators';
import { FieldError } from '../../../shared/forms/field-error';
import { applyServerErrors } from '../../../shared/forms/server-errors';
import { PageHeader } from '../../../shared/ui/page-header';

/** Creates a company (PENDING) and invites its first Company Admin in one step. */
@Component({
  selector: 'occpl-company-create',
  imports: [ReactiveFormsModule, RouterLink, PageHeader, FieldError],
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
    @if (createdId(); as id) {
      <p class="field-error" role="alert">
        The company was created, but some images could not be uploaded.
        <a [routerLink]="['/companies', id]">Open the company</a> to add them again.
      </p>
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
            <label for="taxId">Tax ID (any country)</label>
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
      <section class="card" formGroupName="profile">
        <h2>Address, statutory and website <span class="muted small">(optional)</span></h2>
        <div class="form">
          <div class="field">
            <label for="p-line1">Address line 1</label>
            <input id="p-line1" formControlName="addressLine1" />
          </div>
          <div class="field">
            <label for="p-line2">Address line 2</label>
            <input id="p-line2" formControlName="addressLine2" />
          </div>
          <div class="field">
            <label for="p-city">City</label>
            <input id="p-city" formControlName="city" />
          </div>
          <div class="field">
            <label for="p-state">State</label>
            <input id="p-state" formControlName="state" />
          </div>
          <div class="field">
            <label for="p-postal">Postal code</label>
            <input id="p-postal" formControlName="postalCode" aria-describedby="p-postal-error" />
            <occpl-field-error
              [control]="form.controls.profile.controls.postalCode"
              errorId="p-postal-error"
            />
          </div>
          <div class="field">
            <label for="p-website">Website</label>
            <input
              id="p-website"
              formControlName="website"
              placeholder="https://example.com"
              aria-describedby="p-website-error"
            />
            <occpl-field-error
              [control]="form.controls.profile.controls.website"
              errorId="p-website-error"
            />
          </div>
          <div class="field">
            <label for="p-gst">GST number</label>
            <input
              id="p-gst"
              formControlName="gstNumber"
              maxlength="15"
              aria-describedby="p-gst-error"
            />
            <occpl-field-error
              [control]="form.controls.profile.controls.gstNumber"
              errorId="p-gst-error"
            />
          </div>
          <div class="field">
            <label for="p-pan">PAN</label>
            <input
              id="p-pan"
              formControlName="panNumber"
              maxlength="10"
              aria-describedby="p-pan-error"
            />
            <occpl-field-error
              [control]="form.controls.profile.controls.panNumber"
              errorId="p-pan-error"
            />
          </div>
        </div>
      </section>
      <section class="card" formGroupName="primary">
        <h2>Primary contact <span class="muted small">(optional)</span></h2>
        <p class="muted small">
          Products created for this company use it as their first administrator, so enter all three.
        </p>
        <div class="form">
          <div class="field">
            <label for="pc-name">Name</label>
            <input id="pc-name" formControlName="name" />
          </div>
          <div class="field">
            <label for="pc-email">Email</label>
            <input id="pc-email" type="email" formControlName="email" />
          </div>
          <div class="field">
            <label for="pc-phone">Phone</label>
            <input
              id="pc-phone"
              formControlName="phone"
              placeholder="+919876543210"
              aria-describedby="pc-phone-error"
            />
            <occpl-field-error
              [control]="form.controls.primary.controls.phone"
              errorId="pc-phone-error"
            />
          </div>
        </div>
      </section>
      <section class="card" formGroupName="support">
        <h2>Support contact <span class="muted small">(optional)</span></h2>
        <div class="form">
          <div class="field">
            <label for="sc-name">Name</label>
            <input id="sc-name" formControlName="name" />
          </div>
          <div class="field">
            <label for="sc-email">Support email</label>
            <input id="sc-email" type="email" formControlName="email" />
          </div>
          <div class="field">
            <label for="sc-phone">Support phone</label>
            <input
              id="sc-phone"
              formControlName="phone"
              placeholder="+912012345678"
              aria-describedby="sc-phone-error"
            />
            <occpl-field-error
              [control]="form.controls.support.controls.phone"
              errorId="sc-phone-error"
            />
          </div>
        </div>
      </section>
      <section class="card">
        <h2>Logo and favicon <span class="muted small">(optional)</span></h2>
        <div class="form">
          <div class="field">
            <label for="logo">Logo</label>
            <input
              id="logo"
              type="file"
              accept="image/png,image/jpeg,image/webp"
              (change)="pick('logo', $event)"
            />
            <span class="hint">{{ limits.logo.label }}</span>
          </div>
          <div class="field">
            <label for="favicon">Favicon</label>
            <input
              id="favicon"
              type="file"
              accept="image/png,image/jpeg,image/webp,image/x-icon,.ico"
              (change)="pick('favicon', $event)"
            />
            <span class="hint">{{ limits.favicon.label }}</span>
          </div>
        </div>
        @for (message of imageErrors(); track message) {
          <p class="field-error" role="alert">{{ message }}</p>
        }
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
  protected readonly imageErrors = signal<string[]>([]);
  protected readonly createdId = signal<string | null>(null);
  protected readonly limits = IMAGE_LIMITS;
  private readonly images: { logo?: File; favicon?: File } = {};

  protected pick(kind: 'logo' | 'favicon', event: Event): void {
    const file = (event.target as HTMLInputElement).files?.[0];
    const problem = file ? imageProblem(kind, file) : null;
    this.imageErrors.update((list) => list.filter((m) => !m.includes(kind)));
    if (!file || problem) {
      delete this.images[kind];
      if (problem) {
        this.imageErrors.update((list) => [...list, problem]);
        (event.target as HTMLInputElement).value = '';
      }
      return;
    }
    this.images[kind] = file;
  }
  protected readonly form = this.fb.group({
    displayName: ['', [Validators.required, Validators.maxLength(200)]],
    legalName: ['', [Validators.required, Validators.maxLength(200)]],
    code: ['', [Validators.required, Validators.pattern(/^[a-z0-9](?:[a-z0-9-]{0,61}[a-z0-9])?$/)]],
    country: ['IN', [Validators.required, Validators.pattern(/^[A-Z]{2}$/)]],
    taxId: ['', Validators.maxLength(64)],
    profile: this.fb.group(profileControls()),
    primary: this.fb.group({
      name: ['', Validators.maxLength(200)],
      email: ['', [Validators.email, Validators.maxLength(320)]],
      phone: ['', Validators.pattern(PHONE)],
    }),
    support: this.fb.group({
      name: ['', Validators.maxLength(200)],
      email: ['', [Validators.email, Validators.maxLength(320)]],
      phone: ['', Validators.pattern(PHONE)],
    }),
    timeZone: ['Asia/Kolkata', Validators.maxLength(64)],
    initialAdmin: this.fb.group({
      email: ['', [Validators.required, Validators.email, Validators.maxLength(320)]],
      firstName: ['', Validators.maxLength(100)],
      lastName: ['', Validators.maxLength(100)],
    }),
  });

  /** Contact from a form group: null when left empty, 'invalid' (with errors shown) when half filled. */
  private contactOf(
    group: 'primary' | 'support',
    phoneRequired: boolean,
  ): Contact | null | 'invalid' {
    const g = this.form.controls[group];
    const v = g.getRawValue();
    const name = v.name.trim();
    const email = v.email.trim();
    const phone = v.phone.trim();
    if (!name && !email && !phone) {
      return null;
    }
    const missing = (ctl: 'name' | 'email' | 'phone', value: string, needed: boolean) => {
      if (needed && !value) {
        g.controls[ctl].setErrors({ required: true });
        g.controls[ctl].markAsTouched();
        return true;
      }
      return false;
    };
    const bad = [
      missing('name', name, true),
      missing('email', email, true),
      missing('phone', phone, phoneRequired),
    ];
    return bad.some(Boolean) || g.invalid ? 'invalid' : { name, email, phone: phone || null };
  }

  private profileOf() {
    const p = this.form.controls.profile.getRawValue();
    return Object.fromEntries(
      Object.entries(p)
        .map(([k, value]) => [k, value.trim()])
        .filter(([, value]) => value),
    );
  }

  /** Uploads the chosen images; true when any failed (the company already exists). */
  private async uploadImages(id: string): Promise<boolean> {
    let failed = false;
    for (const kind of ['logo', 'favicon'] as const) {
      const file = this.images[kind];
      if (!file) {
        continue;
      }
      try {
        await firstValueFrom(this.api.setImage(id, kind, file));
      } catch (e) {
        failed = true;
        this.imageErrors.update((list) => [
          ...list,
          ...applyServerErrors(this.form, e).map((m) => `${kind}: ${m}`),
        ]);
      }
    }
    return failed;
  }

  protected async submit(): Promise<void> {
    this.form.markAllAsTouched();
    const primary = this.contactOf('primary', true);
    const support = this.contactOf('support', false);
    if (this.form.invalid || primary === 'invalid' || support === 'invalid') {
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
          profile: this.profileOf(),
          primaryContact: primary,
          supportContact: support,
          initialAdmin: {
            email: v.initialAdmin.email.trim(),
            firstName: v.initialAdmin.firstName.trim() || null,
            lastName: v.initialAdmin.lastName.trim() || null,
          },
        }),
      );
      const failed = await this.uploadImages(company.id);
      if (failed) {
        this.createdId.set(company.id);
        return;
      }
      await this.router.navigate(['/companies', company.id]);
    } catch (e) {
      this.formErrors.set(applyServerErrors(this.form, e));
    } finally {
      this.saving.set(false);
    }
  }
}
