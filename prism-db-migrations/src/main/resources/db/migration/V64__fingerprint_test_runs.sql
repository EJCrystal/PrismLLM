-- 模型指纹检测历史。参考库是闭集，结果只用于渠道对比，保留最近 100 次。
create table fingerprint_test_runs (
    id bigserial primary key,
    channel_id varchar(36) not null,
    channel_name varchar(100) not null,
    channel_key_id varchar(36),
    channel_key_name varchar(100),
    model varchar(200) not null,
    protocol varchar(32) not null,
    success boolean not null,
    status_code integer,
    latency_ms bigint not null,
    decision varchar(32) not null,
    prediction varchar(120),
    prediction_name varchar(120),
    probability double precision,
    used_outputs integer not null,
    total_outputs integer not null,
    analysis text not null,
    samples text not null,
    error_message text,
    input_tokens bigint,
    output_tokens bigint,
    cached_tokens bigint,
    reasoning_tokens bigint,
    total_tokens bigint,
    created_at bigint not null,
    constraint ck_fingerprint_test_runs_decision
        check (decision in ('scored', 'partial', 'not_model', 'unscorable')),
    constraint ck_fingerprint_test_runs_latency check (latency_ms >= 0),
    constraint ck_fingerprint_test_runs_probability
        check (probability is null or probability between 0 and 1),
    constraint ck_fingerprint_test_runs_outputs
        check (used_outputs between 0 and 3 and total_outputs between 1 and 3)
);

create index idx_fingerprint_test_runs_created_at on fingerprint_test_runs (created_at desc);