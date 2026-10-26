-- Bot username moves to git integration.
-- Copy the username from the first bot (lowest id) with a non-blank username to the git integration.
-- If several bots share an integration with different usernames, only that bot's username is kept.
-- Blank usernames count as missing: the former integration form always submitted the (hidden)
-- username field, so non-Bitbucket integrations saved via the UI store '' instead of NULL.
-- Bitbucket is excluded from the copy: its username is the Atlassian account e-mail used for API
-- authentication, which a bot username can never be. A blank one gets the placeholder instead.
Update git_integrations gi
set username = coalesce(
    (Select trim(b.username) from bots b
        where b.git_integration_id = gi.id and trim(b.username) <> ''
            and gi.provider_type <> 'BITBUCKET'
        order by b.id limit 1),
    'please-fill-git-username'
)
where gi.username is NULL or trim(gi.username) = '';

-- Drop Bot username
Alter Table bots Drop COLUMN username;

-- Make new username column not nullable
Alter Table git_integrations Alter Column username SET NOT NULL;
