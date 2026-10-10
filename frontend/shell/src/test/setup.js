import '@testing-library/jest-dom';

// Mock axios to avoid network calls during tests
import { vi } from 'vitest';

// Mock the eip-shell-context modules
vi.mock('eip-shell-context/useRole', () => ({
  useRole: () => ({
    isPlatformAdmin: false,
    isAdmin: true,
    isIngestionAdmin: false,
    isDataAiAdmin: false,
    isSecopsAdmin: false,
  }),
}));

vi.mock('eip-shell-context/AuthProvider', () => ({
  useAuth: () => ({
    logout: vi.fn(),
  }),
}));

vi.mock('eip-shell-context/ToastProvider', () => ({
  useToast: () => ({
    success: vi.fn(),
    error: vi.fn(),
  }),
}));

vi.mock('eip-shell-context/actingTenant', () => ({
  getActingTenant: () => ({ tenantId: 'test-tenant-id' }),
  onActingTenantChange: vi.fn(),
}));

vi.mock('eip-shell-context/tenantResolver', () => ({
  tenantRedirectUri: () => 'http://localhost:3001',
}));