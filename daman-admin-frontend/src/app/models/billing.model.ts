export interface Billing {
  id: number;
  clientCode: string;
  licenseId?: number | null;
  amount?: number | null;
  paymentMethod?: string | null;
  paymentStatus?: string | null;
  invoiceRef?: string | null;
  paymentDate?: string | null;
  supportStartDate?: string | null;
  supportEndDate?: string | null;
  notes?: string | null;
  createdAt: string;
}

export interface BillingRequest {
  licenseId?: number | null;
  amount?: number | null;
  paymentMethod?: string;
  paymentStatus?: string;
  invoiceRef?: string;
  paymentDate?: string;
  supportStartDate?: string;
  supportEndDate?: string;
  notes?: string;
}
