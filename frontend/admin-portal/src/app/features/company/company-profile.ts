import { ChangeDetectionStrategy, Component, inject, signal } from '@angular/core';
import { NonNullableFormBuilder, ReactiveFormsModule } from '@angular/forms';
import { firstValueFrom } from 'rxjs';
import { CompaniesApi } from '../../core/api/companies.api';
import { ProfileInput } from '../../core/api/models';
import { FieldError } from '../../shared/forms/field-error';
import { IMAGE_LIMITS, imageProblem, profileControls } from '../../shared/forms/profile-validators';
import { applyServerErrors } from '../../shared/forms/server-errors';
import { CompanyContext } from './company-context';

/** Optional company profile: website, address, GST/PAN, logo and favicon. */
@Component({
  selector: 'occpl-company-profile',
  imports: [ReactiveFormsModule, FieldError],
  template: `
    @if (context.company(); as c) {
      <section class="card">
        <div class="row">
          <h2>Profile</h2>
          <span class="spacer"></span>
          @if (canManage() && !editing()) {
            <button type="button" class="btn small" (click)="edit()">Edit</button>
          }
        </div>
        @for (message of errors(); track message) {
          <p class="field-error" role="alert">{{ message }}</p>
        }
        @if (editing()) {
          <form class="form" [formGroup]="form" (ngSubmit)="save()">
            <div class="field">
              <label for="pr-line1">Address line 1</label>
              <input id="pr-line1" formControlName="addressLine1" />
            </div>
            <div class="field">
              <label for="pr-line2">Address line 2</label>
              <input id="pr-line2" formControlName="addressLine2" />
            </div>
            <div class="field">
              <label for="pr-city">City</label>
              <input id="pr-city" formControlName="city" />
            </div>
            <div class="field">
              <label for="pr-state">State</label>
              <input id="pr-state" formControlName="state" />
            </div>
            <div class="field">
              <label for="pr-postal">Postal code</label>
              <input
                id="pr-postal"
                formControlName="postalCode"
                aria-describedby="pr-postal-error"
              />
              <occpl-field-error [control]="form.controls.postalCode" errorId="pr-postal-error" />
            </div>
            <div class="field">
              <label for="pr-website">Website</label>
              <input
                id="pr-website"
                formControlName="website"
                placeholder="https://example.com"
                aria-describedby="pr-website-error"
              />
              <occpl-field-error [control]="form.controls.website" errorId="pr-website-error" />
            </div>
            <div class="field">
              <label for="pr-gst">GST number</label>
              <input
                id="pr-gst"
                formControlName="gstNumber"
                maxlength="15"
                aria-describedby="pr-gst-error"
              />
              <occpl-field-error [control]="form.controls.gstNumber" errorId="pr-gst-error" />
            </div>
            <div class="field">
              <label for="pr-pan">PAN</label>
              <input
                id="pr-pan"
                formControlName="panNumber"
                maxlength="10"
                aria-describedby="pr-pan-error"
              />
              <occpl-field-error [control]="form.controls.panNumber" errorId="pr-pan-error" />
            </div>
            @if (!canEditLegal()) {
              <p class="hint full muted small">GST and PAN can only be changed by OCCPL.</p>
            }
            <div class="form-actions">
              <button type="submit" class="btn primary">Save</button>
              <button type="button" class="btn" (click)="editing.set(false)">Cancel</button>
            </div>
          </form>
        } @else {
          <dl class="details">
            <dt>Address</dt>
            <dd>{{ address() }}</dd>
            <dt>Website</dt>
            <dd>
              @if (c.profile.website) {
                <a [href]="c.profile.website" target="_blank" rel="noopener noreferrer">{{
                  c.profile.website
                }}</a>
              } @else {
                —
              }
            </dd>
            <dt>GST number</dt>
            <dd>{{ c.profile.gstNumber ?? '—' }}</dd>
            <dt>PAN</dt>
            <dd>{{ c.profile.panNumber ?? '—' }}</dd>
          </dl>
        }
        <h3>Logo and favicon</h3>
        <div class="images">
          @for (kind of kinds; track kind) {
            <div class="image">
              <strong>{{ kind === 'logo' ? 'Logo' : 'Favicon' }}</strong>
              @if (url(kind); as src) {
                <img
                  [src]="src"
                  [alt]="kind === 'logo' ? 'Company logo' : 'Company favicon'"
                  [class]="kind"
                />
              } @else {
                <span class="muted small">Not set</span>
              }
              @if (canManage()) {
                <label class="btn small" [for]="'img-' + kind">{{
                  url(kind) ? 'Replace' : 'Upload'
                }}</label>
                <input
                  class="visually-hidden"
                  type="file"
                  [id]="'img-' + kind"
                  [accept]="
                    kind === 'logo'
                      ? 'image/png,image/jpeg,image/webp'
                      : 'image/png,image/jpeg,image/webp,image/x-icon,.ico'
                  "
                  (change)="upload(kind, $event)"
                />
                @if (url(kind)) {
                  <button type="button" class="btn small danger" (click)="remove(kind)">
                    Remove
                  </button>
                }
                <span class="hint small muted">{{ limits[kind].label }}</span>
              }
            </div>
          }
        </div>
      </section>
    }
  `,
  styles: `
    .images {
      display: flex;
      flex-wrap: wrap;
      gap: var(--occpl-space-6);
    }
    .image {
      display: flex;
      flex-direction: column;
      align-items: flex-start;
      gap: var(--occpl-space-2);
    }
    img.logo {
      max-height: 4.5rem;
      max-width: 14rem;
    }
    img.favicon {
      width: 2rem;
      height: 2rem;
    }
    .visually-hidden {
      position: absolute;
      width: 1px;
      height: 1px;
      overflow: hidden;
      clip: rect(0 0 0 0);
    }
  `,
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class CompanyProfile {
  private readonly api = inject(CompaniesApi);
  private readonly fb = inject(NonNullableFormBuilder);
  protected readonly context = inject(CompanyContext);

  protected readonly kinds = ['logo', 'favicon'] as const;
  protected readonly limits = IMAGE_LIMITS;
  protected readonly editing = signal(false);
  protected readonly errors = signal<string[]>([]);
  protected readonly form = this.fb.group(profileControls());

  protected canManage(): boolean {
    return this.context.can('company.profile.manage', 'platform.companies.manage');
  }

  /** Company admins may change address and website; GST and PAN are legal identity. */
  protected canEditLegal(): boolean {
    return this.context.isPlatform('platform.companies.manage');
  }

  protected url(kind: 'logo' | 'favicon'): string | null {
    const p = this.context.company()?.profile;
    return (kind === 'logo' ? p?.logoUrl : p?.faviconUrl) ?? null;
  }

  protected address(): string {
    const p = this.context.company()?.profile;
    const parts = [p?.addressLine1, p?.addressLine2, p?.city, p?.state, p?.postalCode].filter(
      Boolean,
    );
    return parts.length ? parts.join(', ') : '—';
  }

  protected edit(): void {
    const p = this.context.company()?.profile;
    this.form.setValue({
      website: p?.website ?? '',
      addressLine1: p?.addressLine1 ?? '',
      addressLine2: p?.addressLine2 ?? '',
      city: p?.city ?? '',
      state: p?.state ?? '',
      postalCode: p?.postalCode ?? '',
      gstNumber: p?.gstNumber ?? '',
      panNumber: p?.panNumber ?? '',
    });
    for (const name of ['gstNumber', 'panNumber'] as const) {
      const control = this.form.controls[name];
      if (this.canEditLegal()) {
        control.enable();
      } else {
        control.disable();
      }
    }
    this.errors.set([]);
    this.editing.set(true);
  }

  protected async save(): Promise<void> {
    const c = this.context.company();
    this.form.markAllAsTouched();
    if (!c || this.form.invalid) {
      return;
    }
    const v = this.form.getRawValue();
    const profile: ProfileInput = {
      website: v.website.trim(),
      addressLine1: v.addressLine1.trim(),
      addressLine2: v.addressLine2.trim(),
      city: v.city.trim(),
      state: v.state.trim(),
      postalCode: v.postalCode.trim(),
      ...(this.canEditLegal()
        ? {
            gstNumber: v.gstNumber.trim().toUpperCase(),
            panNumber: v.panNumber.trim().toUpperCase(),
          }
        : {}),
    };
    try {
      this.context.set(await firstValueFrom(this.api.update(c, { profile })));
      this.editing.set(false);
    } catch (e) {
      this.errors.set(applyServerErrors(this.form, e));
    }
  }

  protected async upload(kind: 'logo' | 'favicon', event: Event): Promise<void> {
    const input = event.target as HTMLInputElement;
    const file = input.files?.[0];
    input.value = '';
    if (!file) {
      return;
    }
    const problem = imageProblem(kind, file);
    if (problem) {
      this.errors.set([problem]);
      return;
    }
    try {
      this.context.set(await firstValueFrom(this.api.setImage(this.context.id(), kind, file)));
      this.errors.set([]);
    } catch (e) {
      this.errors.set(applyServerErrors(this.form, e));
    }
  }

  protected async remove(kind: 'logo' | 'favicon'): Promise<void> {
    try {
      this.context.set(await firstValueFrom(this.api.removeImage(this.context.id(), kind)));
      this.errors.set([]);
    } catch (e) {
      this.errors.set(applyServerErrors(this.form, e));
    }
  }
}
