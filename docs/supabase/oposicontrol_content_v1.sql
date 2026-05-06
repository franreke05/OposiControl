create extension if not exists pgcrypto;

create table if not exists public.specialties (
    id uuid primary key default gen_random_uuid(),
    name text not null,
    description text not null default '',
    is_active boolean not null default true,
    sort_order integer not null default 0,
    created_at timestamptz not null default timezone('utc', now()),
    updated_at timestamptz not null default timezone('utc', now())
);

create table if not exists public.exams (
    id uuid primary key default gen_random_uuid(),
    specialty_id uuid not null references public.specialties(id) on delete restrict,
    title text not null,
    description text not null default '',
    status text not null default 'draft' check (status in ('draft', 'published', 'archived')),
    publish_from timestamptz null,
    sort_order integer not null default 0,
    created_at timestamptz not null default timezone('utc', now()),
    updated_at timestamptz not null default timezone('utc', now())
);

create table if not exists public.exam_questions (
    id uuid primary key default gen_random_uuid(),
    exam_id uuid not null references public.exams(id) on delete cascade,
    title text not null,
    statement text not null,
    sort_order integer not null default 0,
    created_at timestamptz not null default timezone('utc', now()),
    updated_at timestamptz not null default timezone('utc', now())
);

create table if not exists public.question_options (
    id uuid primary key default gen_random_uuid(),
    question_id uuid not null references public.exam_questions(id) on delete cascade,
    label text not null check (label in ('A', 'B', 'C', 'D')),
    content text not null,
    is_correct boolean not null default false,
    sort_order integer not null default 0,
    unique (question_id, label)
);

create table if not exists public.question_resources (
    id uuid primary key default gen_random_uuid(),
    question_id uuid not null references public.exam_questions(id) on delete cascade,
    type text not null check (type in ('image', 'audio', 'video', 'text', 'document')),
    title text not null default '',
    text_body text null,
    storage_path text null,
    public_url text null,
    mime_type text null,
    sort_order integer not null default 0,
    check (
        (type = 'text' and coalesce(text_body, '') <> '')
        or
        (type <> 'text' and coalesce(public_url, '') <> '')
    )
);

create index if not exists idx_exams_specialty_id on public.exams (specialty_id);
create index if not exists idx_exam_questions_exam_id on public.exam_questions (exam_id);
create index if not exists idx_question_options_question_id on public.question_options (question_id);
create index if not exists idx_question_resources_question_id on public.question_resources (question_id);

create or replace function public.set_updated_at()
returns trigger
language plpgsql
as $$
begin
    new.updated_at = timezone('utc', now());
    return new;
end;
$$;

drop trigger if exists trg_specialties_updated_at on public.specialties;
create trigger trg_specialties_updated_at
before update on public.specialties
for each row execute function public.set_updated_at();

drop trigger if exists trg_exams_updated_at on public.exams;
create trigger trg_exams_updated_at
before update on public.exams
for each row execute function public.set_updated_at();

drop trigger if exists trg_exam_questions_updated_at on public.exam_questions;
create trigger trg_exam_questions_updated_at
before update on public.exam_questions
for each row execute function public.set_updated_at();

alter table public.specialties enable row level security;
alter table public.exams enable row level security;
alter table public.exam_questions enable row level security;
alter table public.question_options enable row level security;
alter table public.question_resources enable row level security;

drop policy if exists "content read authenticated" on public.specialties;
create policy "content read authenticated" on public.specialties
for select to authenticated using (true);

drop policy if exists "content write authenticated" on public.specialties;
drop policy if exists "content write service role" on public.specialties;
create policy "content write service role" on public.specialties
for all to service_role using (true) with check (true);

drop policy if exists "exams read authenticated" on public.exams;
create policy "exams read authenticated" on public.exams
for select to authenticated using (true);

drop policy if exists "exams write authenticated" on public.exams;
drop policy if exists "exams write service role" on public.exams;
create policy "exams write service role" on public.exams
for all to service_role using (true) with check (true);

drop policy if exists "questions read authenticated" on public.exam_questions;
create policy "questions read authenticated" on public.exam_questions
for select to authenticated using (true);

drop policy if exists "questions write authenticated" on public.exam_questions;
drop policy if exists "questions write service role" on public.exam_questions;
create policy "questions write service role" on public.exam_questions
for all to service_role using (true) with check (true);

drop policy if exists "options read authenticated" on public.question_options;
create policy "options read authenticated" on public.question_options
for select to authenticated using (true);

drop policy if exists "options write authenticated" on public.question_options;
drop policy if exists "options write service role" on public.question_options;
create policy "options write service role" on public.question_options
for all to service_role using (true) with check (true);

drop policy if exists "resources read authenticated" on public.question_resources;
create policy "resources read authenticated" on public.question_resources
for select to authenticated using (true);

drop policy if exists "resources write authenticated" on public.question_resources;
drop policy if exists "resources write service role" on public.question_resources;
create policy "resources write service role" on public.question_resources
for all to service_role using (true) with check (true);

insert into storage.buckets (id, name, public)
values ('exam-resources', 'exam-resources', true)
on conflict (id) do nothing;

drop policy if exists "exam resources public read" on storage.objects;
create policy "exam resources public read" on storage.objects
for select to public
using (bucket_id = 'exam-resources');

drop policy if exists "exam resources authenticated write" on storage.objects;
drop policy if exists "exam resources service write" on storage.objects;
create policy "exam resources service write" on storage.objects
for all to service_role
using (bucket_id = 'exam-resources')
with check (bucket_id = 'exam-resources');
