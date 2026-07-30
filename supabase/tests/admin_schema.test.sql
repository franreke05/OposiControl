begin;

select plan(35);

select ok(
    to_regclass('public.app_permission') is not null,
    'app_permission exists'
);
select ok(
    to_regclass('public.role_permission') is not null,
    'role_permission exists'
);
select ok(
    to_regclass('public.opcion_pregunta') is not null,
    'normalized question options exist'
);
select ok(
    to_regclass('private.api_idempotency') is not null,
    'idempotency store is private'
);
select ok(
    to_regclass('public.admin_preference') is not null,
    'per-admin remote preferences exist'
);
select ok(
    exists (
        select 1
        from pg_indexes
        where schemaname = 'public'
          and indexname = 'idx_usuario_auth_user_unique'
    ),
    'auth subject maps to at most one usuario'
);
select ok(
    exists (
        select 1
        from pg_proc
        join pg_namespace on pg_namespace.oid = pg_proc.pronamespace
        where pg_namespace.nspname = 'public'
          and pg_proc.proname = 'admin_permissions_for_subject'
    ),
    'backend RBAC lookup RPC exists'
);
select ok(
    not has_function_privilege(
        'anon',
        'public.admin_permissions_for_subject(uuid)',
        'EXECUTE'
    ),
    'anonymous role cannot resolve permissions'
);
select ok(
    not has_function_privilege(
        'authenticated',
        'public.admin_permissions_for_subject(uuid)',
        'EXECUTE'
    ),
    'user JWT cannot resolve permissions for another subject'
);
select ok(
    has_function_privilege(
        'service_role',
        'public.admin_permissions_for_subject(uuid)',
        'EXECUTE'
    ),
    'service role can resolve backend permissions'
);
select ok(
    exists (
        select 1
        from pg_policies
        where schemaname = 'public'
          and tablename = 'usuario'
          and policyname = 'oposicontrol_admin_select'
    ),
    'usuario has permission-based RLS'
);
select ok(
    not exists (
        select 1
        from pg_policies
        where schemaname = 'public'
          and tablename = 'audit_log'
          and cmd in ('INSERT', 'UPDATE', 'DELETE', 'ALL')
          and roles @> array['authenticated'::name]
    ),
    'authenticated users cannot forge audit rows directly'
);
select is(
    (
        select public
        from storage.buckets
        where id = 'exam-resources'
    ),
    false,
    'premium exam resources bucket is private'
);
select is(
    (
        select public
        from storage.buckets
        where id = 'exam-covers'
    ),
    false,
    'exam covers use object-level publication policy'
);
select ok(
    exists (
        select 1
        from pg_proc
        join pg_namespace on pg_namespace.oid = pg_proc.pronamespace
        where pg_namespace.nspname = 'public'
          and pg_proc.proname = 'admin_import_exam_questions'
    ),
    'atomic question import RPC exists'
);
select ok(
    exists (
        select 1
        from pg_proc
        join pg_namespace on pg_namespace.oid = pg_proc.pronamespace
        where pg_namespace.nspname = 'public'
          and pg_proc.proname = 'admin_reorder_exam_questions'
    ),
    'atomic question reorder RPC exists'
);
select ok(
    exists (
        select 1
        from pg_policies
        where schemaname = 'storage'
          and tablename = 'objects'
          and policyname = 'oposicontrol exam covers public read'
    ),
    'published covers have an object-level read policy'
);
select ok(
    has_function_privilege(
        'service_role',
        'public.admin_enqueue_storage_cleanup(text,text,text)',
        'EXECUTE'
    ) and not has_function_privilege(
        'authenticated',
        'public.admin_enqueue_storage_cleanup(text,text,text)',
        'EXECUTE'
    ),
    'only service role can enqueue durable storage compensation'
);
select ok(
    has_function_privilege(
        'authenticated',
        'public.admin_save_exam_question(integer,integer,text,text,integer,jsonb,bigint,text)',
        'EXECUTE'
    ),
    'authenticated admin can call the atomic question save RPC'
);
select ok(
    has_function_privilege(
        'authenticated',
        'public.admin_upsert_store_item(integer,jsonb,bigint,text)',
        'EXECUTE'
    ) and has_function_privilege(
        'authenticated',
        'public.admin_upsert_store_offer(integer,jsonb,bigint,text)',
        'EXECUTE'
    ) and has_function_privilege(
        'authenticated',
        'public.admin_upsert_limited_resource(integer,jsonb,bigint,text)',
        'EXECUTE'
    ) and has_function_privilege(
        'authenticated',
        'public.admin_upsert_burst_campaign(integer,jsonb,bigint,text)',
        'EXECUTE'
    ),
    'commerce writes are exposed as audited RPCs'
);
select ok(
    has_function_privilege(
        'authenticated',
        'public.admin_soft_delete_content(text,integer,bigint,text)',
        'EXECUTE'
    ),
    'canonical content soft-delete RPC exists'
);
select ok(
    exists (
        select 1
        from pg_trigger
        where tgrelid = 'public.pregunta'::regclass
          and tgname = 'enforce_question_options_on_question'
          and not tgisinternal
    ),
    'new and edited questions enforce the four-option invariant'
);
select ok(
    exists (
        select 1
        from pg_trigger
        where tgrelid = 'public.opcion_pregunta'::regclass
          and tgname = 'enforce_question_options_on_option'
          and not tgisinternal
    ),
    'option replacements enforce the four-option invariant'
);
select ok(
    exists (
        select 1
        from pg_trigger
        where tgrelid = 'public.examen'::regclass
          and tgname = 'guard_exam_publication'
          and not tgisinternal
    ),
    'exam publication has a permission and content guard'
);
select ok(
    not has_table_privilege(
        'authenticated',
        'public.articulo_tienda',
        'INSERT'
    ) and not has_table_privilege(
        'authenticated',
        'public.articulo_tienda',
        'UPDATE'
    ) and not has_table_privilege(
        'authenticated',
        'public.articulo_tienda',
        'DELETE'
    ),
    'authenticated users cannot bypass audited commerce RPCs'
);
select ok(
    not has_table_privilege(
        'authenticated',
        'public.examen',
        'DELETE'
    ) and not has_table_privilege(
        'authenticated',
        'public.pregunta',
        'DELETE'
    ),
    'canonical content cannot be hard-deleted directly'
);
select ok(
    (
        select count(*) = 5
        from information_schema.columns
        where table_schema = 'public'
          and table_name = 'usuario'
          and column_name in (
              'apellidos',
              'username',
              'especialidad_registro',
              'perfil_completo',
              'perfil_completado_at'
          )
    ),
    'consumer registration profile columns exist'
);
select ok(
    exists (
        select 1
        from pg_indexes
        where schemaname = 'public'
          and indexname = 'idx_usuario_username_active_unique'
          and indexdef ilike '%unique%'
          and indexdef ilike '%deleted_at is null%'
    ),
    'active consumer usernames are unique'
);
select ok(
    to_regclass('private.oposibots_oauth_credential') is not null,
    'OAuth credential replay store is private'
);
select ok(
    exists (
        select 1
        from pg_indexes
        where schemaname = 'private'
          and indexname = 'idx_oposibots_oauth_credential_expires_at'
    ),
    'OAuth replay expiry cleanup is indexed'
);
select ok(
    has_function_privilege(
        'service_role',
        'public.oposibots_complete_registration_profile(uuid,text,text,text,text,text)',
        'EXECUTE'
    ) and not has_function_privilege(
        'anon',
        'public.oposibots_complete_registration_profile(uuid,text,text,text,text,text)',
        'EXECUTE'
    ) and not has_function_privilege(
        'authenticated',
        'public.oposibots_complete_registration_profile(uuid,text,text,text,text,text)',
        'EXECUTE'
    ),
    'only service role can complete a consumer profile'
);
select ok(
    has_function_privilege(
        'service_role',
        'public.oposibots_claim_oauth_credential(text,text,timestamp with time zone)',
        'EXECUTE'
    ) and not has_function_privilege(
        'anon',
        'public.oposibots_claim_oauth_credential(text,text,timestamp with time zone)',
        'EXECUTE'
    ) and not has_function_privilege(
        'authenticated',
        'public.oposibots_claim_oauth_credential(text,text,timestamp with time zone)',
        'EXECUTE'
    ),
    'only service role can claim an OAuth credential'
);
select ok(
    has_table_privilege(
        'service_role',
        'private.oposibots_oauth_credential',
        'SELECT,INSERT,DELETE'
    ) and not has_table_privilege(
        'anon',
        'private.oposibots_oauth_credential',
        'SELECT,INSERT,UPDATE,DELETE'
    ) and not has_table_privilege(
        'authenticated',
        'private.oposibots_oauth_credential',
        'SELECT,INSERT,UPDATE,DELETE'
    ),
    'only service role can use the OAuth replay store'
);
select ok(
    not exists (
        select 1
        from pg_class
        join pg_namespace on pg_namespace.oid = pg_class.relnamespace
        cross join lateral aclexplode(
            coalesce(pg_class.relacl, acldefault('r', pg_class.relowner))
        ) as acl
        where pg_namespace.nspname = 'private'
          and pg_class.relname = 'oposibots_oauth_credential'
          and acl.grantee = 0
          and acl.privilege_type in ('SELECT', 'INSERT', 'UPDATE', 'DELETE')
    ),
    'OAuth replay store has no PUBLIC data privileges'
);
select is(
    (
        select count(*)
        from private.migration_guardrail
        where resolved_at is null
    ),
    0::bigint,
    'fresh schema has no unresolved migration guardrails'
);

select * from finish();

rollback;
