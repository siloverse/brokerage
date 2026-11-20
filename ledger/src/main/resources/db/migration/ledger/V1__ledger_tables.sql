create table accounts (
    id          bigint generated always as identity primary key,
    uuid        uuid        not null unique,
    user_uuid   uuid,
    type        text        not null,
    asset_type  text        not null,
    asset_code  text        not null,
    booked      bigint      not null default 0,
    reserved    bigint      not null default 0,
    available   bigint      generated always as (booked - reserved) stored,
    updated_at  timestamptz not null default now(),
    created_at  timestamptz not null default now(),

    constraint accounts_type_known        check (type in ('USER', 'FEE_INCOME', 'VENUE_SETTLEMENT', 'SUSPENSE')),
    constraint accounts_asset_type_known  check (asset_type in ('CASH', 'POSITION')),
    constraint accounts_user_iff_user     check ((type = 'USER') = (user_uuid is not null)),
    constraint accounts_reserved_positive check (reserved >= 0),
    constraint accounts_no_overdraft      check (type <> 'USER' or booked - reserved >= 0),
    constraint accounts_holds_users_only  check (type = 'USER' or reserved = 0),
    constraint accounts_one_per_asset     unique nulls not distinct (user_uuid, type, asset_type, asset_code)
);

create table holds (
    id          bigint generated always as identity primary key,
    uuid        uuid        not null unique,
    account_id  bigint      not null,
    reference   uuid        not null,
    amount      bigint      not null,
    consumed    bigint      not null default 0,
    released    bigint      not null default 0,
    created_at  timestamptz not null default now(),

    constraint holds_account_fk          foreign key (account_id) references accounts (id),
    constraint holds_reference_once      unique (reference),
    constraint holds_amount_positive     check (amount > 0),
    constraint holds_parts_not_negative  check (consumed >= 0 and released >= 0),
    constraint holds_not_overused        check (consumed + released <= amount)
);

create table transfers (
    id               bigint generated always as identity primary key,
    uuid             uuid        not null unique,
    kind             text        not null,
    idempotency_key  uuid        not null,
    hold_id          bigint,
    created_at       timestamptz not null default now(),

    constraint transfers_kind_known     check (kind in ('DEPOSIT', 'WITHDRAWAL', 'FILL')),
    constraint transfers_key_once       unique (kind, idempotency_key),
    constraint transfers_fill_has_hold  check (kind <> 'FILL' or hold_id is not null),
    constraint transfers_hold_fk        foreign key (hold_id) references holds (id)
);

create table ledger_entries (
    id           bigint generated always as identity primary key,
    transfer_id  bigint      not null,
    account_id   bigint      not null,
    amount       bigint      not null,
    created_at   timestamptz not null default now(),

    constraint ledger_entries_transfer_fk     foreign key (transfer_id) references transfers (id),
    constraint ledger_entries_account_fk      foreign key (account_id) references accounts (id),
    constraint ledger_entries_amount_not_zero check (amount <> 0)
);