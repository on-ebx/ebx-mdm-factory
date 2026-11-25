# Company pack — what the model should know before adapting it

A **Company** is a legal entity the organisation does business with or belongs to: a customer, a supplier, a partner,
a subsidiary. One record per legal entity, never per site, per contract or per role: a site is an address of the company,
a contract belongs to another domain, a role (customer, supplier) is a flag or a child row, not a second company.

## What the core holds and why

- `Identification/name` is the usual name (what people type and search); `legalName` is the registered name. Both exist on
  purpose: never merge them.
- `Legal/registrationNumber` is the number of the registration authority of the jurisdiction (SIREN in France, Companies
  House number in the UK). Other identifiers (VAT, LEI, DUNS, SIRET of the head office) are rows of `CompanyIdentifier`,
  one per type, with validity dates: a company can change its VAT number, keep the history.
- `HeadOffice` is the legal seat, one per company, denormalised on the record because almost every screen shows it.
  Every other address (billing, delivery, sites) is a row of `CompanyAddress`.
- `Lifecycle/status` is the governance state of the record (DRAFT, IN_REVIEW, ACTIVE, BLOCKED, ARCHIVED), driven by the
  workflows; a business status (prospect, customer) is a separate field or a role.
- `Contact` rows are people; the company's own switchboard, generic e-mail and website are the `contacts` block on the
  company itself.

## Typical customer variations (propose them as differences, do not assume them)

- **Supplier reference**: payment terms, incoterm, purchasing category, a supplier number from the ERP → fields of a
  `Purchasing` group on `Company` (not a separate table), the ERP number as a `CompanyIdentifier` of type ERP.
- **Compliance documents** (Kbis, insurance certificate, certifications) with an expiry date → a child table
  `ComplianceDocument` (type, file through the attachments block, issuedOn, expiresOn, status computed), not fields on the
  company; the expiry alert is a `periodicReview` workflow on `expiresOn`.
- **Bank accounts** → the `bank-accounts` option (child table with IBAN/BIC checked by the published libraries); a change of
  account is a `changeRequest` workflow with Finance as reviewer.
- **Group structure** (parent, ultimate parent, ownership %) → the `group-hierarchy` option; a plain `parent` foreign key
  is enough when only the direct parent matters.
- **E-invoicing** → the `e-invoicing` option; only when the organisation issues or receives invoices through a platform.
- **Customer-specific**: credit limit, sales territory, account manager → fields of a `Sales` group.

## Rules that are usually wanted

- Uniqueness at entry on the registration number, then on name + head-office postcode (the matching policy of the pack).
- SIREN/SIRET: 9/14 digits and the Luhn library; VAT number: country prefix + the VIES check in a workflow step, never in a
  trigger (outbound calls are capped at 500 ms there).
- A company cannot be ARCHIVED while it has an ACTIVE contract or an open order: say so as a rule the domain owner must
  confirm, since the other tables are outside the pack.

## What not to do

- Do not add a free-text "country" or "currency": use the reference-data pack's tables.
- Do not put addresses or bank accounts as repeated fields on the company: they are child tables.
- Do not place the technical fields of the add-ons (`Linked_Field_*`, `Association_Reverse`) on a form.
