-- Persistent "remember me" login tokens (Spring Security PersistentTokenBasedRememberMeServices).
-- The table matches JdbcTokenRepositoryImpl.CREATE_TABLE_SQL; Flyway owns it, so the
-- repository runs with createTableOnStartup=false.
create table if not exists persistent_logins (
    username  varchar(64) not null,
    series    varchar(64) primary key,
    token     varchar(64) not null,
    last_used timestamp   not null
);
