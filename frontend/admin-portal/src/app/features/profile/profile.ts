import { ChangeDetectionStrategy, Component, OnInit, inject, signal } from '@angular/core';
import { NonNullableFormBuilder, ReactiveFormsModule, Validators } from '@angular/forms';
import { firstValueFrom } from 'rxjs';
import { MeApi } from '../../core/api/me.api';
import { Profile as ProfileModel } from '../../core/api/models';
import { SessionService } from '../../core/session/session.service';
import { FieldError } from '../../shared/forms/field-error';
import { applyServerErrors } from '../../shared/forms/server-errors';
import { PageHeader } from '../../shared/ui/page-header';

@Component({
  selector: 'occpl-profile',
  imports: [ReactiveFormsModule, PageHeader, FieldError],
  template: `
    <occpl-page-header heading="Your profile" [subtitle]="profile()?.email ?? null" />
    @if (saved()) {
      <p class="notice success" role="status">Your profile was saved.</p>
    }
    @for (message of formErrors(); track message) {
      <p class="field-error" role="alert">{{ message }}</p>
    }
    <form class="form card" [formGroup]="form" (ngSubmit)="save()">
      <div class="field">
        <label for="displayName">Display name</label>
        <input
          id="displayName"
          formControlName="displayName"
          autocomplete="name"
          aria-describedby="displayName-error"
        />
        <occpl-field-error [control]="form.controls.displayName" errorId="displayName-error" />
      </div>
      <div class="field">
        <label for="phone">Phone</label>
        <input
          id="phone"
          formControlName="phone"
          autocomplete="tel"
          placeholder="+919876543210"
          aria-describedby="phone-hint phone-error"
        />
        <span class="hint" id="phone-hint">International format</span>
        <occpl-field-error [control]="form.controls.phone" errorId="phone-error" />
      </div>
      <div class="field">
        <label for="locale">Language</label>
        <input
          id="locale"
          formControlName="locale"
          placeholder="en-IN"
          aria-describedby="locale-error"
        />
        <occpl-field-error [control]="form.controls.locale" errorId="locale-error" />
      </div>
      <div class="field">
        <label for="timeZone">Time zone</label>
        <input
          id="timeZone"
          formControlName="timeZone"
          placeholder="Asia/Kolkata"
          aria-describedby="timeZone-error"
        />
        <occpl-field-error [control]="form.controls.timeZone" errorId="timeZone-error" />
      </div>
      <div class="form-actions">
        <button type="submit" class="btn primary" [disabled]="saving()">Save</button>
      </div>
    </form>
  `,
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class Profile implements OnInit {
  private readonly api = inject(MeApi);
  private readonly session = inject(SessionService);
  private readonly fb = inject(NonNullableFormBuilder);

  protected readonly profile = signal<ProfileModel | null>(null);
  protected readonly saving = signal(false);
  protected readonly saved = signal(false);
  protected readonly formErrors = signal<string[]>([]);

  protected readonly form = this.fb.group({
    displayName: ['', [Validators.required, Validators.maxLength(200)]],
    phone: ['', Validators.pattern(/^\+[1-9][0-9]{6,14}$/)],
    locale: ['', Validators.pattern(/^[a-z]{2,3}(-[A-Z]{2})?$/)],
    timeZone: ['', Validators.maxLength(64)],
  });

  async ngOnInit(): Promise<void> {
    const profile = await firstValueFrom(this.api.profile());
    this.profile.set(profile);
    this.form.setValue({
      displayName: profile.displayName ?? '',
      phone: profile.phone ?? '',
      locale: profile.locale ?? '',
      timeZone: profile.timeZone ?? '',
    });
  }

  protected async save(): Promise<void> {
    this.form.markAllAsTouched();
    if (this.form.invalid) {
      return;
    }
    this.saving.set(true);
    this.saved.set(false);
    this.formErrors.set([]);
    const v = this.form.getRawValue();
    try {
      const profile = await firstValueFrom(
        this.api.updateProfile({
          displayName: v.displayName.trim(),
          phone: v.phone.trim() || null,
          locale: v.locale.trim() || null,
          timeZone: v.timeZone.trim() || null,
        }),
      );
      this.profile.set(profile);
      this.saved.set(true);
      await this.session.load(true);
    } catch (e) {
      this.formErrors.set(applyServerErrors(this.form, e));
    } finally {
      this.saving.set(false);
    }
  }
}
