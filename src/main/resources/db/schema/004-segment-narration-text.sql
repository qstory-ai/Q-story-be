-- Replaces the narration_stale flag with the text the rendered audio actually says.
--
-- 003 added a boolean set on every text edit. It could only ever be conservative: reverting an
-- utterance to its original wording put the text back in step with the recording, but the flag had
-- no way to know that and kept claiming stale. Storing what was rendered makes staleness a
-- comparison instead of a guess - and one that cannot drift, because it is derived on read.

alter table if exists public.story_segment
    add column if not exists narration_text text;

drop index if exists public.idx_story_segment_narration_stale;

alter table if exists public.story_segment
    drop column if exists narration_stale;
