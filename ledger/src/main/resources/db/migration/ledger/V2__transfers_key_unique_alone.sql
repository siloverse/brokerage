alter table transfers drop constraint transfers_key_once;
alter table transfers add constraint transfers_key_once unique (idempotency_key);