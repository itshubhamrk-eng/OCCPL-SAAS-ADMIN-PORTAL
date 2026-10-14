import { Routes } from '@angular/router';
import { authGuard, companyGuard, companyPermissionGuard, platformGuard } from './core/auth/guards';
import { homeRedirect } from './features/auth/home-redirect';
import { Shell } from './layout/shell/shell';

const P = 'platform.';

export const routes: Routes = [
  {
    path: 'auth/callback',
    title: 'Signing in',
    loadComponent: () => import('./features/auth/callback').then((m) => m.AuthCallback),
  },
  {
    path: 'signed-out',
    title: 'Signed out',
    loadComponent: () => import('./features/auth/status-page').then((m) => m.StatusPage),
    data: {
      heading: 'You are signed out',
      message: 'Thank you for using the OCCPL Admin Portal.',
      action: 'signIn',
    },
  },
  {
    path: '',
    component: Shell,
    canActivate: [authGuard],
    children: [
      { path: '', pathMatch: 'full', canActivate: [homeRedirect], children: [] },
      {
        path: 'profile',
        title: 'Your profile',
        loadComponent: () => import('./features/profile/profile').then((m) => m.Profile),
      },
      {
        path: 'invitations/accept',
        title: 'Accept invitation',
        loadComponent: () =>
          import('./features/invitations/accept-invitation').then((m) => m.AcceptInvitation),
      },
      {
        path: 'platform',
        canActivate: [platformGuard()],
        children: [
          {
            path: '',
            pathMatch: 'full',
            title: 'Platform dashboard',
            loadComponent: () =>
              import('./features/platform/dashboard').then((m) => m.PlatformDashboard),
          },
          {
            path: 'companies',
            canActivate: [platformGuard(P + 'companies.read')],
            title: 'Companies',
            loadComponent: () =>
              import('./features/platform/companies/companies-list').then((m) => m.CompaniesList),
          },
          {
            path: 'companies/new',
            canActivate: [platformGuard(P + 'companies.manage')],
            title: 'New company',
            loadComponent: () =>
              import('./features/platform/companies/company-create').then((m) => m.CompanyCreate),
          },
          {
            path: 'users',
            canActivate: [platformGuard(P + 'users.read')],
            title: 'Users',
            loadComponent: () =>
              import('./features/platform/users/users-list').then((m) => m.UsersList),
          },
          {
            path: 'users/:userId',
            canActivate: [platformGuard(P + 'users.read')],
            title: 'User',
            loadComponent: () =>
              import('./features/platform/users/user-detail').then((m) => m.UserDetail),
          },
          {
            path: 'products',
            canActivate: [platformGuard(P + 'products.read')],
            title: 'Products',
            loadComponent: () =>
              import('./features/platform/products/products-list').then((m) => m.ProductsList),
          },
          {
            path: 'products/new',
            canActivate: [platformGuard(P + 'products.manage')],
            title: 'Register product',
            loadComponent: () =>
              import('./features/platform/products/product-register').then(
                (m) => m.ProductRegister,
              ),
          },
          {
            path: 'products/:productId',
            canActivate: [platformGuard(P + 'products.read')],
            title: 'Product',
            loadComponent: () =>
              import('./features/platform/products/product-detail').then((m) => m.ProductDetail),
          },
          {
            path: 'plans',
            canActivate: [platformGuard(P + 'plans.read')],
            title: 'Plans',
            loadComponent: () =>
              import('./features/platform/plans/plans-list').then((m) => m.PlansList),
          },
          {
            path: 'plans/new',
            canActivate: [platformGuard(P + 'plans.manage')],
            title: 'New plan',
            loadComponent: () =>
              import('./features/platform/plans/plan-editor').then((m) => m.PlanEditor),
          },
          {
            path: 'plans/:planId',
            canActivate: [platformGuard(P + 'plans.read')],
            title: 'Plan',
            loadComponent: () =>
              import('./features/platform/plans/plan-detail').then((m) => m.PlanDetail),
          },
          {
            path: 'plans/:planId/edit',
            canActivate: [platformGuard(P + 'plans.manage')],
            title: 'Edit plan',
            loadComponent: () =>
              import('./features/platform/plans/plan-editor').then((m) => m.PlanEditor),
          },
          {
            path: 'subscriptions',
            canActivate: [platformGuard(P + 'subscriptions.read')],
            title: 'Subscriptions',
            loadComponent: () =>
              import('./features/platform/subscriptions/subscriptions-list').then(
                (m) => m.SubscriptionsList,
              ),
          },
          {
            path: 'invoices',
            canActivate: [platformGuard(P + 'subscriptions.read')],
            title: 'Invoices',
            loadComponent: () =>
              import('./features/platform/invoices-list').then((m) => m.InvoicesList),
          },
          {
            path: 'provisioning',
            canActivate: [platformGuard(P + 'provisioning.read')],
            title: 'Provisioning',
            loadComponent: () =>
              import('./features/platform/provisioning/jobs-list').then((m) => m.JobsList),
          },
          {
            path: 'provisioning/:jobId',
            canActivate: [platformGuard(P + 'provisioning.read')],
            title: 'Provisioning job',
            loadComponent: () =>
              import('./features/platform/provisioning/job-detail').then((m) => m.JobDetail),
          },
          {
            path: 'audit',
            canActivate: [platformGuard(P + 'audit.read')],
            title: 'Audit log',
            loadComponent: () => import('./features/audit/audit-log').then((m) => m.AuditLog),
          },
        ],
      },
      {
        path: 'companies/:companyId',
        canActivate: [companyGuard],
        loadComponent: () =>
          import('./features/company/company-workspace').then((m) => m.CompanyWorkspace),
        children: [
          {
            path: '',
            pathMatch: 'full',
            title: 'Company',
            canActivate: [companyPermissionGuard('company.profile.read', P + 'companies.read')],
            loadComponent: () =>
              import('./features/company/company-overview').then((m) => m.CompanyOverview),
          },
          {
            path: 'products',
            title: 'Products',
            canActivate: [companyPermissionGuard('company.products.launch', P + 'companies.read')],
            loadComponent: () =>
              import('./features/company/company-products').then((m) => m.CompanyProducts),
          },
          {
            path: 'subscription',
            title: 'Subscription',
            canActivate: [
              companyPermissionGuard('company.subscription.read', P + 'subscriptions.read'),
            ],
            loadComponent: () =>
              import('./features/company/company-subscription').then((m) => m.CompanySubscription),
          },
          {
            path: 'users',
            title: 'Users',
            canActivate: [companyPermissionGuard('company.users.read', P + 'companies.read')],
            loadComponent: () =>
              import('./features/company/company-members').then((m) => m.CompanyMembers),
          },
          {
            path: 'billing',
            title: 'Billing',
            canActivate: [companyPermissionGuard('company.billing.read', P + 'subscriptions.read')],
            loadComponent: () =>
              import('./features/company/company-billing').then((m) => m.CompanyBilling),
          },
          {
            path: 'usage',
            title: 'Usage',
            canActivate: [companyPermissionGuard('company.usage.read', P + 'subscriptions.read')],
            loadComponent: () =>
              import('./features/company/company-usage').then((m) => m.CompanyUsage),
          },
          {
            path: 'audit',
            title: 'Audit log',
            canActivate: [companyPermissionGuard('company.audit.read', P + 'audit.read')],
            loadComponent: () => import('./features/audit/audit-log').then((m) => m.AuditLog),
          },
        ],
      },
      {
        path: 'forbidden',
        title: 'Access denied',
        loadComponent: () => import('./features/auth/status-page').then((m) => m.StatusPage),
        data: {
          heading: 'Access denied',
          message: 'You do not have permission to open this page.',
        },
      },
      {
        path: 'mfa-required',
        title: 'Authenticator required',
        loadComponent: () => import('./features/auth/status-page').then((m) => m.StatusPage),
        data: {
          heading: 'Sign in with your authenticator app',
          message:
            'OCCPL staff permissions are only available after signing in with an authenticator code.',
          action: 'mfa',
        },
      },
      {
        path: 'no-access',
        title: 'No company access',
        loadComponent: () => import('./features/auth/status-page').then((m) => m.StatusPage),
        data: {
          heading: 'No company access yet',
          message:
            'Your account is not a member of any company. Ask your company administrator for an invitation.',
        },
      },
      {
        path: '**',
        title: 'Page not found',
        loadComponent: () => import('./features/auth/status-page').then((m) => m.StatusPage),
      },
    ],
  },
];
