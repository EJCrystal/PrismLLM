alter table channels add column quota_credential_ciphertext text not null default '';
alter table channels add column quota_credential_nonce text not null default '';
alter table channels add column quota_credential_key_version integer not null default 1;
