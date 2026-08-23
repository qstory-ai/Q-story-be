-- Lets an asset record where a re-rendered file came from.
--
-- Until now every asset was a file shipped with the frontend build, so "which version of this
-- clip?" had one answer. Narration re-rendered from an edited line is produced at runtime and
-- stored remotely, so the row has to say when that happened and what it was rendered from -
-- otherwise a re-render is indistinguishable from the original recording after the fact.

alter table if exists public.story_asset
    add column if not exists rendered_at timestamp(6) with time zone;

-- The voice the clip was rendered with, so a cast change can be detected the same way an edited
-- line is: by comparison rather than by remembering to flag it.
alter table if exists public.story_asset
    add column if not exists rendered_voice varchar(64);
