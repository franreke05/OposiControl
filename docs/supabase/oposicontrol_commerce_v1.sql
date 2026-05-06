create table if not exists public.store_items (
  id uuid primary key default gen_random_uuid(),
  name text not null,
  description text not null default '',
  type text not null check (type in ('consumable', 'protector', 'streak', 'exam_access', 'resource')),
  price_tickets integer not null default 0 check (price_tickets >= 0),
  status text not null default 'draft' check (status in ('draft', 'active', 'scheduled', 'expired', 'archived')),
  sort_order integer not null default 0,
  created_at timestamptz not null default timezone('utc', now()),
  updated_at timestamptz not null default timezone('utc', now())
);

create table if not exists public.store_offers (
  id uuid primary key default gen_random_uuid(),
  item_id uuid not null references public.store_items(id) on delete cascade,
  title text not null,
  discount_percent integer check (discount_percent between 0 and 100),
  special_price_tickets integer check (special_price_tickets >= 0),
  starts_at timestamptz,
  ends_at timestamptz,
  status text not null default 'draft' check (status in ('draft', 'active', 'scheduled', 'expired', 'archived')),
  check (discount_percent is not null or special_price_tickets is not null)
);

create table if not exists public.limited_resources (
  id uuid primary key default gen_random_uuid(),
  item_id uuid not null references public.store_items(id) on delete cascade,
  resource_id text not null,
  title text not null,
  starts_at timestamptz,
  ends_at timestamptz,
  access_duration_hours integer not null default 72 check (access_duration_hours > 0),
  status text not null default 'draft' check (status in ('draft', 'active', 'scheduled', 'expired', 'archived'))
);

create table if not exists public.burst_campaigns (
  id uuid primary key default gen_random_uuid(),
  title text not null,
  description text not null default '',
  type text not null check (type in ('daily_streak', 'exam_burst', 'ticket_multiplier', 'resource_unlock')),
  starts_at timestamptz,
  ends_at timestamptz,
  multiplier numeric not null default 1 check (multiplier >= 1),
  reward_tickets integer not null default 0 check (reward_tickets >= 0),
  linked_exam_id uuid references public.exams(id) on delete set null,
  linked_store_item_id uuid references public.store_items(id) on delete set null,
  status text not null default 'draft' check (status in ('draft', 'active', 'scheduled', 'expired', 'archived'))
);

alter table public.store_items enable row level security;
alter table public.store_offers enable row level security;
alter table public.limited_resources enable row level security;
alter table public.burst_campaigns enable row level security;

drop policy if exists "commerce read authenticated" on public.store_items;
create policy "commerce read authenticated" on public.store_items for select to authenticated using (true);
drop policy if exists "commerce write authenticated" on public.store_items;
drop policy if exists "commerce write service role" on public.store_items;
create policy "commerce write service role" on public.store_items for all to service_role using (true) with check (true);

drop policy if exists "offers read authenticated" on public.store_offers;
create policy "offers read authenticated" on public.store_offers for select to authenticated using (true);
drop policy if exists "offers write authenticated" on public.store_offers;
drop policy if exists "offers write service role" on public.store_offers;
create policy "offers write service role" on public.store_offers for all to service_role using (true) with check (true);

drop policy if exists "limited resources read authenticated" on public.limited_resources;
create policy "limited resources read authenticated" on public.limited_resources for select to authenticated using (true);
drop policy if exists "limited resources write authenticated" on public.limited_resources;
drop policy if exists "limited resources write service role" on public.limited_resources;
create policy "limited resources write service role" on public.limited_resources for all to service_role using (true) with check (true);

drop policy if exists "burst read authenticated" on public.burst_campaigns;
create policy "burst read authenticated" on public.burst_campaigns for select to authenticated using (true);
drop policy if exists "burst write authenticated" on public.burst_campaigns;
drop policy if exists "burst write service role" on public.burst_campaigns;
create policy "burst write service role" on public.burst_campaigns for all to service_role using (true) with check (true);
