create table iq_questions (
    id bigserial primary key,
    title varchar(120) not null,
    prompt text not null,
    answer_type varchar(10) not null,
    expected_answer text not null,
    enabled boolean not null default true,
    updated_at bigint not null,
    constraint ck_iq_questions_answer_type check (answer_type in ('number', 'text')),
    constraint ck_iq_questions_title check (length(trim(title)) > 0),
    constraint ck_iq_questions_prompt check (length(trim(prompt)) > 0)
);

insert into iq_questions(title, prompt, answer_type, expected_answer, enabled, updated_at)
values (
    '糖果组合推理',
    '一只黑色袋子里有苹果、桃子、西瓜三种口味的糖果，糖果有圆形和五角星形两种形状，两种形状可以靠手感区分。各口味与形状的数量如下：

| 形状 | 苹果味 | 桃子味 | 西瓜味 |
| --- | --- | --- | --- |
| 圆形 | 7 | 9 | 8 |
| 五角星形 | 7 | 6 | 4 |

活动开始前先决定一共取出多少颗糖果。最少取出多少颗，才能保证手中一定同时拥有“不同形状的苹果味和桃子味糖果”（即：圆形苹果味 + 五角星形桃子味，或五角星形苹果味 + 圆形桃子味）？',
    'number', '21', true, (extract(epoch from now()) * 1000)::bigint
);

alter table iq_test_runs add column question_id bigint;
alter table iq_test_runs add column question_title varchar(120);
alter table iq_test_runs add column answer_type varchar(10);
alter table iq_test_runs add column expected_answer_value text;
alter table iq_test_runs add column actual_answer text;
