alter table ledger_entries
    add column original_transaction_group_id varchar(180);

create index idx_ledger_original_group on ledger_entries(original_transaction_group_id);
