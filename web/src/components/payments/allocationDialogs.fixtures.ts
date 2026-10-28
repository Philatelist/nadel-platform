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
    valid: true,
    problems: [],
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
      resultLines: [],
      allocatedTotal: '300.00',
      automaticTotal: '0.00',
      prepayment: '0.00',
    },
  };
}

/** A preview the server answers with 200 and a problem: the table is there, `valid` is false and there is no digest. */
export function exceedingProposalOf(requested: string) {
  const ok = proposalOf(requested, '0.00', 'unused');
  const { digest, ...withoutDigest } = ok;
  void digest;
  return {
    ...withoutDigest,
    valid: false,
    problems: [{ code: 'ALLOCATION_EXCEEDS_CHARGE', plotId: 'p9', line: 0, chargeId: 'c1' }],
    proposal: { ...ok.proposal, allocatedTotal: requested },
  };
}
