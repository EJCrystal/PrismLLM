create table model_test_settings (
    id integer primary key,
    default_prompt text not null,
    default_reasoning_effort varchar(20),
    updated_at bigint not null,
    constraint model_test_settings_singleton check (id = 1)
);

insert into model_test_settings (id, default_prompt, default_reasoning_effort, updated_at)
values (1, '请只回复 OK', null, (extract(epoch from now()) * 1000)::bigint);
