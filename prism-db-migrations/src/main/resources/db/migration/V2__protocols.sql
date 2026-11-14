alter table channels
    add column protocols varchar(100) not null default 'chat_completions';

alter table request_logs
    add column protocol varchar(40) not null default 'chat_completions';
