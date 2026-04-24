create table if not exists review_session_participants (
    session_id bigint not null,
    participant_login varchar(255) not null
);

alter table review_session_participants
    add constraint fk_review_session_participants_session
    foreign key (session_id) references review_sessions(id);

create index if not exists idx_review_session_participants_session_id
    on review_session_participants(session_id);
