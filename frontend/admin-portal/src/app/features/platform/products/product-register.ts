import { ChangeDetectionStrategy, Component, inject, signal } from '@angular/core';
import { NonNullableFormBuilder, ReactiveFormsModule, Validators } from '@angular/forms';
import { Router } from '@angular/router';
import { firstValueFrom } from 'rxjs';
import { CatalogApi } from '../../../core/api/catalog.api';
import { FieldError } from '../../../shared/forms/field-error';
import { applyServerErrors } from '../../../shared/forms/server-errors';
import { PageHeader } from '../../../shared/ui/page-header';
import { ProductFields } from './product-fields';

export const AUDIENCE_PATTERN = /^[a-z][a-z0-9-]{2,99}$/;

@Component({
  selector: 'occpl-product-register',
  imports: [ReactiveFormsModule, PageHeader, FieldError, ProductFields],
  template: `
    <occpl-page-header
      heading="Register product"
      backLink="/platform/products"
      backLabel="Products"
      subtitle="Products start as drafts; publish a configuration before activating."
    />
    @for (message of formErrors(); track message) {
      <p class="field-error" role="alert">{{ message }}</p>
    }
    <form class="card stack" [formGroup]="form" (ngSubmit)="submit()">
      <div class="field">
        <label for="code">Code</label>
        <input id="code" formControlName="code" aria-describedby="code-hint code-error" />
        <span class="hint" id="code-hint"
          >Lower-case letters, digits and hyphens; cannot be changed later</span
        >
        <occpl-field-error [control]="form.controls.code" errorId="code-error" />
      </div>
      <occpl-product-fields [group]="form" />
      <div class="form-actions">
        <button type="submit" class="btn primary" [disabled]="saving()">Register</button>
      </div>
    </form>
  `,
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class ProductRegister {
  private readonly api = inject(CatalogApi);
  private readonly router = inject(Router);
  private readonly fb = inject(NonNullableFormBuilder);
  protected readonly saving = signal(false);
  protected readonly formErrors = signal<string[]>([]);
  protected readonly form = this.fb.group({
    code: ['', [Validators.required, Validators.pattern(/^[a-z][a-z0-9-]{1,49}$/)]],
    name: ['', [Validators.required, Validators.maxLength(200)]],
    appUrl: ['', [Validators.required, Validators.maxLength(500)]],
    apiBaseUrl: ['', Validators.maxLength(500)],
    apiAudience: ['', Validators.pattern(AUDIENCE_PATTERN)],
    serviceClientId: ['', Validators.pattern(AUDIENCE_PATTERN)],
    description: ['', Validators.maxLength(2000)],
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
      const product = await firstValueFrom(
        this.api.register({
          code: v.code,
          name: v.name.trim(),
          appUrl: v.appUrl.trim(),
          apiBaseUrl: v.apiBaseUrl.trim() || undefined,
          apiAudience: v.apiAudience.trim() || undefined,
          serviceClientId: v.serviceClientId.trim() || undefined,
          description: v.description.trim() || undefined,
        }),
      );
      await this.router.navigate(['/platform/products', product.id]);
    } catch (e) {
      this.formErrors.set(applyServerErrors(this.form, e));
    } finally {
      this.saving.set(false);
    }
  }
}
