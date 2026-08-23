-- Marks an utterance whose text no longer matches its pre-rendered narration.
--
-- Fixed narration is TTS rendered ahead of time and keyed by the segment's position, so editing an
-- utterance's text in the database leaves the audio saying the old line while the caption shows the
-- new one. The content pipeline caught this (manifest.test.ts compares the two), but nothing did
-- once text became editable at runtime. The flag makes the mismatch a fact the API can report and
-- a re-render can clear, instead of a silent drift a child hears.

alter table if exists public.story_segment
    add column if not exists narration_stale boolean not null default false;

-- "Which lines still need re-recording?" is the question a re-render pass asks.
create index if not exists idx_story_segment_narration_stale
    on public.story_segment (narration_stale)
    where narration_stale;
