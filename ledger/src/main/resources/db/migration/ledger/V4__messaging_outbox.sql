create table messaging_outbox (
    id            bigserial primary key,
    message_id    uuid         not null,
    message_type  varchar(255) not null,
    headers       jsonb        not null,
    payload       bytea        not null,
    published_at  timestamptz,
    created_at    timestamptz  not null default now()
);