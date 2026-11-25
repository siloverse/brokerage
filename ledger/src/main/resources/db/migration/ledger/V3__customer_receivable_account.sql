alter table accounts drop constraint accounts_type_known;
alter table accounts add constraint accounts_type_known
    check (type in ('USER', 'FEE_INCOME', 'VENUE_SETTLEMENT', 'SUSPENSE', 'CUSTOMER_RECEIVABLE'));

alter table accounts drop constraint accounts_user_iff_user;
alter table accounts add constraint accounts_user_iff_user
    check ((type in ('USER', 'CUSTOMER_RECEIVABLE')) = (user_uuid is not null));

alter table accounts add constraint accounts_receivable_not_positive
    check (type <> 'CUSTOMER_RECEIVABLE' or booked <= 0);