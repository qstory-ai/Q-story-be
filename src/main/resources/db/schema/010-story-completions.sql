-- Saved parent reports from finished story sessions (see StoryCompletion.java) - outcomes is the
-- same derived per-question summary the report screen builds from, never a raw recording/transcript.
create table if not exists public.story_completion (
    id uuid primary key,
    user_id uuid not null references public.app_user on delete cascade,
    story_id varchar(64) not null,
    completed_at timestamptz not null,
    duration_seconds integer,
    outcomes jsonb not null,
    created_at timestamptz not null default now()
);

create index if not exists story_completion_user_completed_idx
    on public.story_completion (user_id, completed_at desc);
