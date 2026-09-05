-- Teacher profile images are public, opaque URLs. The object path remains server-only so replacement can clean up the old object.
alter table public.app_user add column if not exists profile_image_url text;
alter table public.app_user add column if not exists profile_image_object_name text;
alter table public.app_user add column if not exists subscription_expires_at timestamp(6) with time zone;
alter table public.organization add column if not exists subscription_expires_at timestamp(6) with time zone;

-- Keep institutional reporting historical when a parent later changes or leaves a class.
alter table public.story_completion
    add column if not exists organization_id uuid references public.organization(id) on delete set null;
alter table public.story_completion
    add column if not exists class_group_id uuid references public.class_group(id) on delete set null;
update public.story_completion completion
set organization_id = user_row.organization_id,
    class_group_id = user_row.class_group_id
from public.app_user user_row
where completion.user_id = user_row.id
  and completion.organization_id is null;
create index if not exists story_completion_organization_completed_idx
    on public.story_completion (organization_id, completed_at desc);
create index if not exists story_completion_class_completed_idx
    on public.story_completion (class_group_id, completed_at desc);

create table if not exists public.payment_order (
    id uuid primary key,
    order_id varchar(64) not null unique,
    user_id uuid not null references public.app_user(id),
    organization_id uuid references public.organization(id),
    target varchar(32) not null,
    status varchar(32) not null,
    amount integer not null check (amount > 0),
    order_name varchar(200) not null,
    payment_key varchar(255),
    paid_at timestamp(6) with time zone,
    access_expires_at timestamp(6) with time zone,
    created_at timestamp(6) with time zone not null,
    updated_at timestamp(6) with time zone not null,
    constraint payment_order_target_check check (target in ('PARENT', 'ORGANIZATION')),
    constraint payment_order_status_check check (status in ('READY', 'PAID', 'FAILED'))
);

create index if not exists payment_order_user_created_idx on public.payment_order (user_id, created_at desc);
