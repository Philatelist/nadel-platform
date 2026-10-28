export const membership = { id: 't1', code: 'MEMBERSHIP', name: 'Membership', nameCustomized: false };
export const electricity = { id: 't2', code: 'ELECTRICITY', name: 'Electricity', nameCustomized: false };

export function proposalLine(
  chargeId: string,
  type: typeof membership,
  month: string,
  open: string,
  proposed: string,
) {
  return {
    chargeId,
    kind: 'REGULAR',
    chargeType: type,
    billingMonth: month,
    serviceFrom: `${month}-01`,
    serviceTo: `${month}-28`,
    accruedOn: `${month}-01`,
    open,
    proposed,
  };
}

export function proposalOf(first: string, second: string, digest: string) {
  return {
    preview: true,
    paymentId: 'pay1',
    digest,
    current: [
      {
        allocationId: 'a1',
        chargeId: 'c1',
        kind: 'REGULAR',
        chargeType: membership,
        billingMonth: '2026-01',
        serviceFrom: '2026-01-01',
        serviceTo: '2026-01-28',
        accruedOn: '2026-01-01',
        amount: '300.00',
      },
    ],
    proposal: {
      plotId: 'p9',
      plotNumber: '9',
      amount: '300.00',
      lines: [
        proposalLine('c1', membership, '2026-01', '600.00', first),
        proposalLine('c2', electricity, '2026-02', '250.00', second),
      ],
      allocatedTotal: '300.00',
      prepayment: '0.00',
    },
  };
}
