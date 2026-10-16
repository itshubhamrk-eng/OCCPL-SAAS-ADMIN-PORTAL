import { DatePipe } from '@angular/common';
import { ChangeDetectionStrategy, Component, OnInit, inject, signal } from '@angular/core';
import { NonNullableFormBuilder, ReactiveFormsModule, Validators } from '@angular/forms';
import { firstValueFrom } from 'rxjs';
import { CompaniesApi } from '../../core/api/companies.api';
import { CompanyContact, CompanySettings, ContactType, StatusChange } from '../../core/api/models';
import { SessionService } from '../../core/session/session.service';
import { FieldError } from '../../shared/forms/field-error';
import { applyServerErrors } from '../../shared/forms/server-errors';
import { ActionDialog } from '../../shared/ui/action-dialog';
import { HumanizePipe } from '../../shared/ui/format.pipes';
import { CompanyContext } from './company-context';

const DOMAIN = /^(?=.{1,253}$)([a-z0-9](?:[a-z0-9-]{0,61}[a-z0-9])?\.)+[a-z]{2,63}$/;
const PHONE_PATTERN = /^\+[1-9][0-9]{6,14}$/;

@Component({
  selector: 'occpl-company-overview',
  imports: [ReactiveFormsModule, ActionDialog, FieldError, DatePipe, HumanizePipe],
  templateUrl: './company-overview.html',
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class CompanyOverview implements OnInit {
  private readonly api = inject(CompaniesApi);
  private readonly fb = inject(NonNullableFormBuilder);
  protected readonly context = inject(CompanyContext);
  protected readonly session = inject(SessionService);

  protected readonly contactTypes: ContactType[] = ['PRIMARY', 'BILLING', 'TECHNICAL'];
  protected readonly settings = signal<CompanySettings | null>(null);
  protected readonly contacts = signal<CompanyContact[]>([]);
  protected readonly history = signal<StatusChange[]>([]);
  protected readonly editingDetails = signal(false);
  protected readonly editingSettings = signal(false);
  protected readonly editingContact = signal<ContactType | null>(null);
  protected readonly errors = signal<string[]>([]);

  protected readonly detailsForm = this.fb.group({
    displayName: ['', [Validators.required, Validators.maxLength(200)]],
    legalName: ['', [Validators.required, Validators.maxLength(200)]],
    country: ['', [Validators.required, Validators.pattern(/^[A-Z]{2}$/)]],
    taxId: ['', Validators.maxLength(64)],
  });

  protected readonly settingsForm = this.fb.group({
    timeZone: ['', [Validators.required, Validators.maxLength(64)]],
    locale: ['', [Validators.required, Validators.pattern(/^[a-z]{2,3}(-[A-Z]{2})?$/)]],
    domains: [''],
  });

  protected readonly contactForm = this.fb.group({
    name: ['', [Validators.required, Validators.maxLength(200)]],
    email: ['', [Validators.required, Validators.email, Validators.maxLength(320)]],
    phone: ['', Validators.pattern(PHONE_PATTERN)],
  });

  ngOnInit(): void {
    const id = this.context.id();
    this.api.settings(id).subscribe((s) => this.settings.set(s));
    this.api.contacts(id).subscribe((c) => this.contacts.set(c));
    this.api.statusHistory(id).subscribe((h) => this.history.set(h));
  }

  protected canManageProfile(): boolean {
    return this.context.can('company.profile.manage', 'platform.companies.manage');
  }

  /** Company admins may change the display name only; legal identity is managed by OCCPL staff. */
  protected canEditLegal(): boolean {
    return this.context.isPlatform('platform.companies.manage');
  }

  protected contact(type: ContactType): CompanyContact | undefined {
    return this.contacts().find((c) => c.type === type);
  }

  // ------------------------------------------------------------ details & status

  protected editDetails(): void {
    const c = this.context.company();
    if (!c) {
      return;
    }
    this.detailsForm.setValue({
      displayName: c.displayName,
      legalName: c.legalName,
      country: c.country,
      taxId: c.taxId ?? '',
    });
    if (!this.canEditLegal()) {
      this.detailsForm.controls.legalName.disable();
      this.detailsForm.controls.country.disable();
      this.detailsForm.controls.taxId.disable();
    }
    this.errors.set([]);
    this.editingDetails.set(true);
  }

  protected async saveDetails(): Promise<void> {
    const c = this.context.company();
    this.detailsForm.markAllAsTouched();
    if (!c || this.detailsForm.invalid) {
      return;
    }
    const v = this.detailsForm.getRawValue();
    const changes = this.canEditLegal()
      ? {
          displayName: v.displayName.trim(),
          legalName: v.legalName.trim(),
          country: v.country,
          taxId: v.taxId.trim(),
        }
      : { displayName: v.displayName.trim() };
    try {
      this.context.set(await firstValueFrom(this.api.update(c, changes)));
      this.editingDetails.set(false);
    } catch (e) {
      this.errors.set(applyServerErrors(this.detailsForm, e));
    }
  }

  protected async changeStatus(
    action: 'activate' | 'suspend' | 'deactivate',
    reason: string | null,
  ): Promise<void> {
    const id = this.context.id();
    this.context.set(await firstValueFrom(this.api.changeStatus(id, action, reason)));
    this.api.statusHistory(id).subscribe((h) => this.history.set(h));
    await this.session.load(true);
  }

  // ------------------------------------------------------------ settings

  protected editSettings(): void {
    const s = this.settings();
    this.settingsForm.setValue({
      timeZone: s?.timeZone ?? 'Asia/Kolkata',
      locale: s?.locale ?? 'en-IN',
      domains: (s?.allowedEmailDomains ?? []).join(', '),
    });
    this.errors.set([]);
    this.editingSettings.set(true);
  }

  protected async saveSettings(): Promise<void> {
    this.settingsForm.markAllAsTouched();
    const v = this.settingsForm.getRawValue();
    const domains = v.domains
      .split(/[\s,]+/)
      .map((d) => d.trim().toLowerCase())
      .filter((d) => d);
    const bad = domains.filter((d) => !DOMAIN.test(d));
    if (bad.length) {
      this.settingsForm.controls.domains.setErrors({
        server: `Not a valid domain: ${bad.join(', ')}`,
      });
      return;
    }
    if (this.settingsForm.invalid) {
      return;
    }
    try {
      this.settings.set(
        await firstValueFrom(
          this.api.saveSettings(
            this.context.id(),
            { timeZone: v.timeZone.trim(), locale: v.locale.trim(), allowedEmailDomains: domains },
            this.settings()?.version ?? 0,
          ),
        ),
      );
      this.editingSettings.set(false);
    } catch (e) {
      this.errors.set(applyServerErrors(this.settingsForm, e));
    }
  }

  // ------------------------------------------------------------ contacts

  protected editContact(type: ContactType): void {
    const existing = this.contact(type);
    this.contactForm.setValue({
      name: existing?.name ?? '',
      email: existing?.email ?? '',
      phone: existing?.phone ?? '',
    });
    // Provisioned products create the first administrator from the primary contact, so it needs a phone.
    const phone = this.contactForm.controls.phone;
    phone.setValidators(
      type === 'PRIMARY'
        ? [Validators.required, Validators.pattern(PHONE_PATTERN)]
        : [Validators.pattern(PHONE_PATTERN)],
    );
    phone.updateValueAndValidity();
    this.errors.set([]);
    this.editingContact.set(type);
  }

  protected async saveContact(): Promise<void> {
    const type = this.editingContact();
    this.contactForm.markAllAsTouched();
    if (!type || this.contactForm.invalid) {
      return;
    }
    const v = this.contactForm.getRawValue();
    try {
      const saved = await firstValueFrom(
        this.api.saveContact(this.context.id(), type, {
          name: v.name.trim(),
          email: v.email.trim(),
          phone: v.phone.trim() || null,
        }),
      );
      this.contacts.update((list) => [...list.filter((c) => c.type !== type), saved]);
      this.editingContact.set(null);
    } catch (e) {
      this.errors.set(applyServerErrors(this.contactForm, e));
    }
  }

  protected async removeContact(type: ContactType): Promise<void> {
    await firstValueFrom(this.api.deleteContact(this.context.id(), type));
    this.contacts.update((list) => list.filter((c) => c.type !== type));
  }
}
