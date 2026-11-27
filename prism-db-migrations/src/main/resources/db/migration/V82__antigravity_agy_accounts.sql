-- Antigravity account identities may be backed by an isolated official agy profile
-- instead of a refresh token. Existing OAuth credentials remain unchanged.
alter table oauth_accounts
    add column auth_method varchar(16) not null default 'oauth',
    add column agy_profile_id varchar(36),
    alter column refresh_token_ciphertext drop not null,
    alter column refresh_token_nonce drop not null,
    alter column refresh_token_key_version drop not null;

alter table oauth_accounts
    add constraint ck_oauth_accounts_auth_method check (
        (auth_method = 'oauth' and agy_profile_id is null
            and refresh_token_ciphertext is not null
            and refresh_token_nonce is not null
            and refresh_token_key_version is not null)
        or (auth_method = 'agy' and provider = 'antigravity' and agy_profile_id is not null
            and refresh_token_ciphertext is null
            and refresh_token_nonce is null
            and refresh_token_key_version is null
            and access_token_ciphertext is null
            and access_token_nonce is null
            and access_token_key_version is null
            and access_token_expires_at is null)
    );

create unique index uk_oauth_accounts_agy_profile
    on oauth_accounts (agy_profile_id)
    where agy_profile_id is not null;
