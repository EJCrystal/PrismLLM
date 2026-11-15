create table iq_test_jobs (
    job_id varchar(36) primary key,
    status varchar(20) not null,
    request_json text not null,
    prompt text not null,
    question_id bigint,
    question_title varchar(120),
    answer_type varchar(10),
    expected_answer_value text,
    judge_id varchar(64),
    run_id bigint,
    error text,
    created_at bigint not null,
    completed_at bigint,
    constraint ck_iq_test_jobs_status
        check (status in ('running', 'completed', 'failed')),
    constraint ck_iq_test_jobs_completed_result
        check ((status = 'completed' and run_id is not null and error is null)
            or (status = 'failed' and run_id is null and error is not null)
            or status = 'running'),
    constraint ck_iq_test_jobs_error_length check (error is null or char_length(error) <= 300)
);

create index idx_iq_test_jobs_status_created_at on iq_test_jobs (status, created_at desc);
