-- 智商检测历史。题目固定，保留最近 200 次运行，供管理台对比模型表现。
create table iq_test_runs (
    id bigserial primary key,
    benchmark varchar(20) not null,
    channel_id varchar(36) not null,
    channel_name varchar(100) not null,
    channel_key_id varchar(36),
    channel_key_name varchar(100),
    model varchar(200) not null,
    upstream_model varchar(200) not null,
    protocol varchar(32) not null,
    success boolean not null,
    status_code integer,
    latency_ms bigint not null,
    verdict varchar(20) not null,
    prompt text not null,
    response_text text,
    expected_answer integer,
    matched_answer integer,
    error_message text,
    input_tokens bigint,
    output_tokens bigint,
    cached_tokens bigint,
    reasoning_tokens bigint,
    total_tokens bigint,
    upstream_reported_model varchar(200),
    judge_id varchar(64),
    judge_label varchar(120),
    judge_verdict varchar(20),
    judge_result_label varchar(120),
    judge_reason text,
    judge_score double precision,
    judge_details text,
    judge_checked_at bigint,
    manual_verdict varchar(20),
    manual_score integer,
    manual_note text,
    manual_updated_at bigint,
    created_at bigint not null,
    constraint ck_iq_test_runs_benchmark check (benchmark in ('pelican', 'candy')),
    constraint ck_iq_test_runs_verdict check (verdict in ('passed', 'incorrect', 'review', 'failed')),
    constraint ck_iq_test_runs_latency check (latency_ms >= 0),
    constraint ck_iq_test_runs_judge_verdict
        check (judge_verdict is null or judge_verdict in ('passed', 'incorrect', 'review', 'failed')),
    constraint ck_iq_test_runs_judge_score
        check (judge_score is null or judge_score between 0 and 100),
    constraint ck_iq_test_runs_manual_verdict
        check (manual_verdict is null or manual_verdict in ('passed', 'incorrect')),
    constraint ck_iq_test_runs_manual_score
        check (manual_score is null or manual_score between 1 and 5)
);

create index idx_iq_test_runs_created_at on iq_test_runs (created_at desc);
create index idx_iq_test_runs_benchmark_created_at on iq_test_runs (benchmark, created_at desc);
