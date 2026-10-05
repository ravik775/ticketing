-- Separation of duties, enforced again by the database: the applicant who owns a ticket can never
-- hold its approval lock, even if they are also an approver in the tenant.
ALTER TABLE ticket_workflow
    ADD CONSTRAINT ck_owner_not_approver CHECK (locked_by IS NULL OR lower(locked_by) <> lower(owner_email));
