# RLS Policies

RLS policies are catalogued from SQL/docs as `DOCUMENTED` and from code as `CODE_EXPECTED`. Effective live policies are `LIVE_VERIFIED: NO`.

No policy is declared secure merely because a SQL file exists. Verification requires authorized read-only inspection plus evidence of the deployed schema; no such inspection occurred here.

OPC-20260819-004 result: connector unavailable (`USER_NOT_LOGGED_IN`) and Supabase CLI absent. No project ref, live policy list, `auth.uid()` behavior, service-role bypass assumptions or advisor output was collected. Classification remains `NOT_VERIFIED / INFRASTRUCTURE_GAP`.
