import { DatePipe } from '@angular/common';
import { ChangeDetectionStrategy, Component, OnInit, inject, signal } from '@angular/core';
import {
  FormsModule,
  NonNullableFormBuilder,
  ReactiveFormsModule,
  Validators,
} from '@angular/forms';
import { firstValueFrom } from 'rxjs';
import { MembersApi } from '../../core/api/members.api';
import { Invitation, Member, MembershipStatus, Page, Role } from '../../core/api/models';
import { SessionService } from '../../core/session/session.service';
import { FieldError } from '../../shared/forms/field-error';
import { applyServerErrors } from '../../shared/forms/server-errors';
import { ActionDialog } from '../../shared/ui/action-dialog';
import { Pager } from '../../shared/ui/pager';
import { StatusBadge } from '../../shared/ui/status-badge';
import { CompanyContext } from './company-context';

type MemberAction = 'suspend' | 'reactivate' | 'deactivate';

/** Members, their roles, and invitations. Core enforces the no-escalation and last-admin rules. */
@Component({
  selector: 'occpl-company-members',
  imports: [
    FormsModule,
    ReactiveFormsModule,
    StatusBadge,
    ActionDialog,
    Pager,
    FieldError,
    DatePipe,
  ],
  templateUrl: './company-members.html',
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class CompanyMembers implements OnInit {
  private readonly api = inject(MembersApi);
  private readonly fb = inject(NonNullableFormBuilder);
  protected readonly context = inject(CompanyContext);
  protected readonly session = inject(SessionService);

  protected readonly members = signal<Page<Member> | null>(null);
  protected readonly invitations = signal<Page<Invitation> | null>(null);
  protected readonly roles = signal<Role[]>([]);
  protected readonly editingRoles = signal<string | null>(null);
  protected readonly roleSelection = signal<Set<string>>(new Set());
  protected readonly inviting = signal(false);
  protected readonly inviteErrors = signal<string[]>([]);
  protected readonly inviteRoles = signal<Set<string>>(new Set(['COMPANY_USER']));
  protected readonly notice = signal<string | null>(null);
  protected q = '';
  protected status = '';
  protected pending: { member: Member; action: MemberAction } | null = null;

  protected readonly inviteForm = this.fb.group({
    email: ['', [Validators.required, Validators.email, Validators.maxLength(320)]],
    firstName: ['', Validators.maxLength(100)],
    lastName: ['', Validators.maxLength(100)],
  });

  protected canManage(): boolean {
    return this.context.can('company.users.manage', 'platform.companies.manage');
  }

  protected canAssignRoles(): boolean {
    return this.context.can('company.roles.assign', 'platform.companies.manage');
  }

  ngOnInit(): void {
    this.search(0);
    this.loadInvitations(0);
    this.api.roles(this.context.id()).subscribe((r) => this.roles.set(r));
  }

  protected search(page: number): void {
    this.api
      .list(this.context.id(), {
        q: this.q.trim() || null,
        status: (this.status || null) as MembershipStatus | null,
        page,
        size: 20,
      })
      .subscribe((p) => this.members.set(p));
  }

  protected loadInvitations(page: number): void {
    this.api
      .invitations(this.context.id(), 'PENDING', page)
      .subscribe((p) => this.invitations.set(p));
  }

  protected isSelf(member: Member): boolean {
    return member.userId === this.session.me()?.id;
  }

  // ------------------------------------------------------------ roles

  protected editRoles(member: Member): void {
    this.roleSelection.set(new Set(member.roles));
    this.editingRoles.set(member.membershipId);
  }

  protected toggleRole(set: 'edit' | 'invite', code: string): void {
    const target = set === 'edit' ? this.roleSelection : this.inviteRoles;
    target.update((s) => {
      const next = new Set(s);
      if (next.has(code)) {
        next.delete(code);
      } else {
        next.add(code);
      }
      return next;
    });
  }

  protected async saveRoles(member: Member): Promise<void> {
    const updated = await firstValueFrom(
      this.api.replaceRoles(this.context.id(), member.membershipId, [...this.roleSelection()]),
    );
    this.replace(updated);
    this.editingRoles.set(null);
  }

  // ------------------------------------------------------------ status

  protected ask(member: Member, action: MemberAction, dialog: ActionDialog): void {
    this.pending = { member, action };
    dialog.open();
  }

  protected async changeStatus(reason: string | null): Promise<void> {
    if (!this.pending) {
      return;
    }
    const { member, action } = this.pending;
    this.replace(
      await firstValueFrom(
        this.api.changeStatus(this.context.id(), member.membershipId, action, reason),
      ),
    );
    this.pending = null;
  }

  private replace(updated: Member): void {
    this.members.update((p) =>
      p
        ? {
            ...p,
            items: p.items.map((m) => (m.membershipId === updated.membershipId ? updated : m)),
          }
        : p,
    );
  }

  // ------------------------------------------------------------ invitations

  protected async invite(): Promise<void> {
    this.inviteForm.markAllAsTouched();
    if (this.inviteForm.invalid || this.inviteRoles().size === 0) {
      return;
    }
    const v = this.inviteForm.getRawValue();
    this.inviteErrors.set([]);
    try {
      const invitation = await firstValueFrom(
        this.api.invite(this.context.id(), {
          email: v.email.trim(),
          roles: [...this.inviteRoles()],
          firstName: v.firstName.trim() || null,
          lastName: v.lastName.trim() || null,
        }),
      );
      this.notice.set(`Invitation sent to ${invitation.email}.`);
      this.inviteForm.reset();
      this.inviteRoles.set(new Set(['COMPANY_USER']));
      this.inviting.set(false);
      this.loadInvitations(0);
      this.search(0);
    } catch (e) {
      this.inviteErrors.set(applyServerErrors(this.inviteForm, e));
    }
  }

  protected async resend(invitation: Invitation): Promise<void> {
    await firstValueFrom(this.api.resend(this.context.id(), invitation.id));
    this.notice.set(`Invitation sent again to ${invitation.email}.`);
    this.loadInvitations(this.invitations()?.page ?? 0);
  }

  protected async revoke(invitation: Invitation): Promise<void> {
    await firstValueFrom(this.api.revoke(this.context.id(), invitation.id));
    this.notice.set(`Invitation to ${invitation.email} revoked.`);
    this.loadInvitations(0);
    this.search(this.members()?.page ?? 0);
  }

  protected roleNames(codes: string[]): string {
    const names = new Map(this.roles().map((r) => [r.code, r.name]));
    return codes.map((c) => names.get(c) ?? c).join(', ');
  }

  protected actionLabel(action: MemberAction | undefined): string {
    return action === 'suspend'
      ? 'Suspend member'
      : action === 'deactivate'
        ? 'Remove member'
        : 'Reactivate member';
  }
}
