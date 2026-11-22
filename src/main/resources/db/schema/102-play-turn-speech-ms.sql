-- Q-58 부모 리포트 "아이가 말한 횟수와 시간": 아이 줄(CHILD, VOICE)마다 녹음 길이(ms)를 남긴다. 앱이 마이크를 멈출 때 잰
-- 값이라 말한 시간의 근사치다(앞뒤 침묵 포함). 글로 쓴 줄과 옛 기록은 null - 리포트는 값이 있는 줄만 더한다.
alter table public.play_turn add column if not exists speech_ms integer;
