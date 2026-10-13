import { Injectable, computed, inject, signal } from '@angular/core';
import { firstValueFrom } from 'rxjs';
import { CompaniesApi } from '../../core/api/companies.api';
import { Company } from '../../core/api/models';
import { SessionService } from '../../core/session/session.service';

/** The company shown in the workspace; provided by {@link CompanyWorkspace} for its pages. */
@Injectable()
export class CompanyContext {
  private readonly api = inject(CompaniesApi);
  private readonly session = inject(SessionService);

  readonly company = signal<Company | null>(null);
  readonly id = computed(() => this.company()?.id ?? '');

  async load(companyId: string): Promise<void> {
    this.company.set(await firstValueFrom(this.api.get(companyId)));
  }

  set(company: Company): void {
    this.company.set(company);
  }

  /** Company permission through membership, or the covering platform permission. */
  can(companyPermission: string, platformPermission?: string): boolean {
    return this.session.can(this.id(), companyPermission, platformPermission);
  }

  isPlatform(permission: string): boolean {
    return this.session.hasPlatform(permission);
  }
}
